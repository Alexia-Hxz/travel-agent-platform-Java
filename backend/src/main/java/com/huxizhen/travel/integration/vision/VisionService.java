package com.huxizhen.travel.integration.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.config.DashScopeProperties;
import com.huxizhen.travel.dto.place.PlaceType;
import com.huxizhen.travel.dto.vision.VisionDocumentType;
import com.huxizhen.travel.dto.vision.VisionItinerarySuggestion;
import com.huxizhen.travel.dto.vision.VisionPlaceSuggestion;
import com.huxizhen.travel.dto.vision.VisionPreview;
import com.huxizhen.travel.integration.llm.DashScopeClient;
import com.huxizhen.travel.integration.llm.LlmException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 多模态图片解析（DashScope {@code qwen3-vl-flash}）。
 *
 * <p>两个用途：
 * <ol>
 *   <li>上传票据 → 结构化预览（用户确认后才入库）；</li>
 *   <li>图片类文件 → 纯文本（原项目这一步用 PaddleOCR，本项目改用同一个视觉模型，理由见
 *       《改写方案》R2：原 OCR 只装了英文模型，中文截图本来就识别不了）。</li>
 * </ol>
 *
 * <p>返回值经过严格归一化：模型说什么不算数，字段/类型/区间都要过一遍才交给前端。
 */
@Service
public class VisionService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration VISION_TIMEOUT = Duration.ofSeconds(60);

    /** 逐字照抄原实现的提示词。 */
    static final String VISION_PROMPT = """
            You extract travel details from one image. Return only JSON with these keys:
            document_type (attraction_photo, hotel_booking, transport_ticket, menu, travel_guide, other),
            summary, place, itinerary, reference_number, raw_text.
            place must be null or {name,type,address,latitude,longitude,image_url,rating,price_level,opening_hours,source_url};
            type must be attraction, hotel, restaurant, airport, or station. Use null for unknown fields.
            itinerary must be null or {date,start_time,end_time,note}; dates use YYYY-MM-DD and times use HH:MM.
            Do not guess facts that are absent from the image. Keep raw_text short and only include legible travel-relevant text.""";

    private static final String TEXT_PROMPT = """
            Read every legible line of text in this image and return only JSON:
            {"raw_text": "<all readable lines, newline separated, original language>"}.
            Preserve line breaks and keep numbers, dates, addresses and order numbers exact.
            If there is no readable text, return {"raw_text": ""}.""";

    private static final Pattern TIME_PATTERN =
            Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d)?$");

    private static final Map<String, PlaceType> PLACE_TYPE_ALIASES = Map.ofEntries(
            Map.entry("景点", PlaceType.ATTRACTION),
            Map.entry("景区", PlaceType.ATTRACTION),
            Map.entry("酒店", PlaceType.HOTEL),
            Map.entry("饭店", PlaceType.HOTEL),
            Map.entry("餐厅", PlaceType.RESTAURANT),
            Map.entry("餐馆", PlaceType.RESTAURANT),
            Map.entry("机场", PlaceType.AIRPORT),
            Map.entry("车站", PlaceType.STATION),
            Map.entry("火车站", PlaceType.STATION),
            Map.entry("高铁站", PlaceType.STATION));

    private final DashScopeClient client;
    private final DashScopeProperties properties;

    public VisionService(DashScopeClient client, DashScopeProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 分析一张图片，给出可复核的预览。 */
    public VisionPreview analyze(Path filePath, String contentType) {
        if (!client.configured()) {
            throw new VisionConfigurationException("多模态解析还没有配置（缺少 DASHSCOPE_API_KEY）。");
        }
        Map<String, Object> payload = ask(filePath, contentType, VISION_PROMPT);
        return normalize(payload);
    }

    /** 只取图片里的文字（图片类文件的 parse 走这条）。 */
    public String extractText(Path filePath, String contentType) {
        if (!client.configured()) {
            throw new VisionConfigurationException("多模态解析还没有配置（缺少 DASHSCOPE_API_KEY）。");
        }
        Map<String, Object> payload = ask(filePath, contentType, TEXT_PROMPT);
        Object raw = payload.get("raw_text");
        return raw == null ? "" : String.valueOf(raw);
    }

    private Map<String, Object> ask(Path filePath, String contentType, String prompt) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(filePath);
        } catch (IOException error) {
            throw new VisionProviderException("视觉模型返回的解析结果不合法。", error);
        }
        String encoded = Base64.getEncoder().encodeToString(bytes);
        String mediaType = normalizeContentType(contentType, filePath);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", Map.of("url", "data:" + mediaType + ";base64," + encoded));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.visionModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", "You are a careful travel-document extraction assistant."),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", prompt),
                        imagePart))));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("temperature", 0);

        String content;
        try {
            content = client.complete(body, VISION_TIMEOUT);
        } catch (LlmException error) {
            throw new VisionProviderException("多模态解析失败，请稍后重试。", error);
        }
        try {
            return decodeModelJson(content);
        } catch (Exception error) {
            throw new VisionProviderException("视觉模型返回的解析结果不合法。", error);
        }
    }

    /** 容忍 ``` 围栏的 JSON 解析。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> decodeModelJson(String content) throws IOException {
        String text = content == null ? "" : content.strip();
        if (text.startsWith("```")) {
            String[] lines = text.split("\\R", -1);
            int start = 1;
            int end = lines.length;
            if (end > start && lines[end - 1].strip().equals("```")) {
                end -= 1;
            }
            text = String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)).strip();
        }
        Object parsed = MAPPER.readValue(text, Object.class);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException("视觉模型返回的内容必须是 JSON 对象。");
        }
        return (Map<String, Object>) map;
    }

    /**
     * 把模型输出变成严格、可复核的预览。
     *
     * <p>规则与原实现逐条对应：字段缺失就整块丢弃，而不是塞一个空壳；类型别名先映射再校验；
     * 超范围的值一律当作没有。
     */
    VisionPreview normalize(Map<String, Object> payload) {
        VisionPlaceSuggestion place = normalizePlace(payload.get("place"));
        VisionItinerarySuggestion itinerary = normalizeItinerary(payload.get("itinerary"));
        String summary = stringOrNull(payload.get("summary"));
        if (summary == null) {
            summary = "No travel details could be extracted.";
        }
        return new VisionPreview(
                VisionDocumentType.from(stringOrNull(payload.get("document_type"))),
                summary.length() > 1000 ? summary.substring(0, 1000) : summary,
                place,
                itinerary,
                truncate(stringOrNull(payload.get("reference_number")), 100),
                stringOrNull(payload.get("raw_text")));
    }

    private VisionPlaceSuggestion normalizePlace(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String name = stringOrNull(map.get("name"));
        String address = stringOrNull(map.get("address"));
        PlaceType type = placeType(map.get("type"));
        if (name == null || address == null || type == null) {
            return null;
        }
        if (name.length() > 160 || address.length() > 300) {
            return null;
        }
        Double latitude = boundedDouble(map.get("latitude"), 90);
        Double longitude = boundedDouble(map.get("longitude"), 180);
        Double rating = boundedDouble(map.get("rating"), 5);
        Integer priceLevel = boundedInt(map.get("price_level"), 0, 5);
        return new VisionPlaceSuggestion(
                name,
                type,
                address,
                latitude,
                longitude,
                truncate(stringOrNull(map.get("image_url")), 500),
                rating,
                priceLevel,
                truncate(stringOrNull(map.get("opening_hours")), 300),
                truncate(stringOrNull(map.get("source_url")), 500));
    }

    private VisionItinerarySuggestion normalizeItinerary(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        String date = stringOrNull(map.get("date"));
        String start = stringOrNull(map.get("start_time"));
        String end = stringOrNull(map.get("end_time"));
        if (date == null || start == null || end == null) {
            return null;
        }
        if (!TIME_PATTERN.matcher(start).matches() || !TIME_PATTERN.matcher(end).matches()) {
            return null;
        }
        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(date);
        } catch (RuntimeException error) {
            return null;
        }
        return new VisionItinerarySuggestion(
                parsedDate, start, end, truncate(stringOrNull(map.get("note")), 500));
    }

    private static PlaceType placeType(Object raw) {
        String value = stringOrNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return PlaceType.fromValue(value);
        } catch (RuntimeException ignored) {
            return PLACE_TYPE_ALIASES.get(value);
        }
    }

    private static Double boundedDouble(Object raw, double limit) {
        if (raw == null) {
            return null;
        }
        try {
            double value = raw instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(raw));
            if (Double.isNaN(value) || Math.abs(value) > limit) {
                return null;
            }
            return value;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Integer boundedInt(Object raw, int min, int max) {
        if (raw == null) {
            return null;
        }
        try {
            int value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
            return value < min || value > max ? null : value;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** {@code application/octet-stream} 的客户端很常见，按扩展名补一个真实类型。 */
    public static String normalizeContentType(String contentType, Path filePath) {
        if (contentType != null && contentType.startsWith("image/")) {
            return contentType;
        }
        String name = filePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return contentType == null ? "application/octet-stream" : contentType;
    }

    /** 未配置模型服务（→ 503）。 */
    public static class VisionConfigurationException extends RuntimeException {
        public VisionConfigurationException(String message) {
            super(message);
        }
    }

    /** 模型调用或结果不可用（→ 422）。 */
    public static class VisionProviderException extends RuntimeException {
        public VisionProviderException(String message, Throwable cause) {
            super(message, cause);
        }

        public VisionProviderException(String message) {
            super(message);
        }
    }
}
