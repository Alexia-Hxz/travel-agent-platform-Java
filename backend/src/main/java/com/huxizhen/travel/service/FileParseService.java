package com.huxizhen.travel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.entity.TripFile;
import com.huxizhen.travel.mapper.TripFileMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 上传资料的解析：PDF 抽文本、图片识别文字，再从文本里用正则挑出结构化字段。
 *
 * <p>与原实现相比唯一的替换是"图片怎么变成文字"（PaddleOCR → 视觉模型），
 * {@code parsed_text} / {@code parsed_result} / 四态状态机与所有错误文案都保持不变。
 */
@Service
public class FileParseService {

    private static final Logger log = LoggerFactory.getLogger(FileParseService.class);
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp");

    /** 结构化结果固定 7 个键，顺序也与原实现一致。 */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("hotel_names", "hotel(?: name)?|酒店(?:名称)?");
        LABELS.put("attraction_names", "attraction(?: name)?|景点(?:名称)?");
        LABELS.put("transport_hubs", "airport|station|机场|车站");
        LABELS.put("addresses", "address|地址");
        LABELS.put("reference_numbers", "order(?: no| number)?|ticket(?: no| number)?|订单号|票号");
    }

    private static final Pattern DATE_PATTERN =
            Pattern.compile("\\b\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\b");
    private static final Pattern TIME_PATTERN =
            Pattern.compile("\\b(?:[01]?\\d|2[0-3]):[0-5]\\d\\b");

    private final TripFileMapper tripFileMapper;
    private final FileStorageService fileStorageService;
    private final ImageTextExtractor imageTextExtractor;
    private final VectorIndexService vectorIndexService;
    private final ObjectMapper objectMapper;

    public FileParseService(
            TripFileMapper tripFileMapper,
            FileStorageService fileStorageService,
            ImageTextExtractor imageTextExtractor,
            VectorIndexService vectorIndexService,
            ObjectMapper objectMapper) {
        this.tripFileMapper = tripFileMapper;
        this.fileStorageService = fileStorageService;
        this.imageTextExtractor = imageTextExtractor;
        this.vectorIndexService = vectorIndexService;
        this.objectMapper = objectMapper;
    }

    /** 解析失败（对应 Python 的 {@code ParseFailure}）。 */
    public static class ParseFailure extends RuntimeException {
        public ParseFailure(String message) {
            super(message);
        }

        public ParseFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public TripFile parse(TripFile tripFile) {
        String extension = FileStorageService.extensionOf(tripFile.getFilePath());
        if (!FileStorageService.ALLOWED_EXTENSIONS.contains(extension)) {
            throw ApiException.badRequest("这种文件类型无法解析。");
        }
        tripFile.setParseStatus("processing");
        tripFileMapper.updateById(tripFile);

        Path path = fileStorageService.resolvePath(tripFile);
        try {
            String text = extractText(path, extension, tripFile.getContentType());
            if (text == null) {
                text = "";
            }
            tripFile.setParsedText(text);
            tripFile.setParsedResult(writeResult(extractStructuredResult(text)));
            tripFile.setParseStatus("completed");
            tripFile.setParseError(null);
            tripFile.setParsedAt(LocalDateTime.now(ZoneOffset.UTC));
        } catch (ParseFailure error) {
            tripFile.setParseStatus("failed");
            tripFile.setParseError(error.getMessage());
            tripFileMapper.updateById(tripFile);
            throw ApiException.unprocessable(error.getMessage());
        } catch (RuntimeException error) {
            tripFile.setParseStatus("failed");
            tripFile.setParseError(error.getMessage() == null ? "文件解析失败。" : error.getMessage());
            tripFileMapper.updateById(tripFile);
            throw ApiException.unprocessable(tripFile.getParseError());
        }
        tripFileMapper.updateById(tripFile);
        // 解析产出文本，索引才让文本可检索。放在这里，上传后不必再做任何操作就能搜到；
        // indexTripFile 从不抛异常，向量库的问题会记在文件行上。
        vectorIndexService.indexTripFile(tripFile);
        return tripFileMapper.selectById(tripFile.getId());
    }

    private String extractText(Path path, String extension, String contentType) {
        if (".pdf".equals(extension)) {
            try (PDDocument document = Loader.loadPDF(path.toFile())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setLineSeparator("\n");
                return stripper.getText(document);
            } catch (IOException | RuntimeException error) {
                throw new ParseFailure("PDF 文本提取失败：" + error.getMessage(), error);
            }
        }
        if (IMAGE_EXTENSIONS.contains(extension)) {
            try {
                return imageTextExtractor.extract(path, contentType);
            } catch (RuntimeException error) {
                // 原始原因只进日志：对外文案与原实现保持一致，不泄露内部细节，但排障要看得到。
                log.warn("image text extraction failed for {}: {}", path.getFileName(), error.toString());
                throw new ParseFailure(
                        "图片文字识别失败（加载或运行识别模型时出错）。"
                                + "请重启后端后重试。",
                        error);
            }
        }
        throw new ParseFailure("只能解析 JPG、JPEG、PNG、WEBP 和 PDF 文件。");
    }

    /**
     * 从文本里抽 7 类字段。
     *
     * <p>标签行用 {@code ^\s*标签\s*[:#]?\s*(.+?)\s*$}（多行、忽略大小写）；日期与时间用独立的
     * 正则并去重保序。
     */
    public static Map<String, List<String>> extractStructuredResult(String text) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String key : List.of("hotel_names", "attraction_names", "transport_hubs")) {
            result.put(key, new ArrayList<>());
        }
        result.put("dates", new ArrayList<>());
        result.put("times", new ArrayList<>());
        for (String key : List.of("addresses", "reference_numbers")) {
            result.put(key, new ArrayList<>());
        }

        for (Map.Entry<String, String> entry : LABELS.entrySet()) {
            Pattern pattern = Pattern.compile(
                    "^\\s*(?:" + entry.getValue() + ")\\s*[:#]?\\s*(.+?)\\s*$",
                    Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            List<String> values = result.get(entry.getKey());
            while (matcher.find()) {
                values.add(matcher.group(1).strip());
            }
        }
        result.put("dates", uniqueMatches(DATE_PATTERN, text));
        result.put("times", uniqueMatches(TIME_PATTERN, text));
        return result;
    }

    private static List<String> uniqueMatches(Pattern pattern, String text) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            seen.add(matcher.group());
        }
        return new ArrayList<>(seen);
    }

    private String writeResult(Map<String, List<String>> result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception error) {
            // 结构化结果写不进去也不该让解析失败：文本已经拿到了。
            return "{}";
        }
    }

    /** 兼容旧调用方的扩展名判断。 */
    public static boolean isParsableFilename(String filename) {
        String extension = FileStorageService.extensionOf(filename == null ? "" : filename.toLowerCase(Locale.ROOT));
        return FileStorageService.ALLOWED_EXTENSIONS.contains(extension);
    }
}
