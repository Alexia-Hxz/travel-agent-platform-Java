package com.huxizhen.travel.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.integration.image.MealDbClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 给每个地点配一张**能打开**的图。
 *
 * <p>三步，每一步都可能失败，失败就退到占位图，绝不让卡片空着：
 * <ol>
 *   <li>Pexels 搜索（按地点名 + 类别 + 城市，横向构图）；</li>
 *   <li>对候选 URL 做可达性探测（真实 GET 前 64KB，4 秒超时）——搜索成功但 CDN 不可达时，
 *       只有探测能发现；</li>
 *   <li>都不行就用内置的分类 SVG。</li>
 * </ol>
 *
 * <p>酒店故意不配图：一张别的酒店的照片不提供任何信息，卡片改为显示评分与房价。
 */
@Service
public class PlaceImageResolver {

    private static final Logger log = LoggerFactory.getLogger(PlaceImageResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(4);
    private static final int MAX_LOOKUPS = 40;
    private static final int PROBE_CANDIDATES = 3;

    private static final List<String> OUTDOOR_HINTS = List.of("attraction", "restaurant");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String pexelsApiKey;
    private final MealDbClient mealDbClient;
    private final Map<String, Boolean> probeCache = new ConcurrentHashMap<>();

    public PlaceImageResolver(
            @Value("${app.pexels.api-key:}") String pexelsApiKey, MealDbClient mealDbClient) {
        this.pexelsApiKey = pexelsApiKey == null ? "" : pexelsApiKey.strip();
        this.mealDbClient = mealDbClient;
    }

    public static String fallbackImage(String category) {
        return fallbackImage(category, null, null);
    }

    /**
     * 分类占位图。餐厅再往下分一层：菜系/招牌菜的占位图比一张通用餐厅图更能说明"这家吃什么"。
     */
    public static String fallbackImage(String category, String name, String dish) {
        String normalized = category == null ? "" : category.toLowerCase(Locale.ROOT);
        if (!Set.of("attraction", "hotel", "restaurant").contains(normalized)) {
            normalized = "generic";
        }
        if (!"restaurant".equals(normalized)) {
            return "/static/place_images/" + normalized + ".svg";
        }
        String text = ((name == null ? "" : name) + " " + (dish == null ? "" : dish)).toLowerCase(Locale.ROOT);
        String variant = "restaurant";
        if (containsAny(text, "寿司", "刺身", "sushi", "生鱼")) {
            variant = "restaurant_sushi";
        } else if (containsAny(text, "拉面", "拉麵", "面", "麵", "noodle", "ramen", "乌冬", "荞麦", "粉")) {
            variant = "restaurant_noodle";
        } else if (containsAny(text, "烧肉", "烤肉", "bbq", "grill", "牛排", "和牛", "串")) {
            variant = "restaurant_grill";
        } else if (containsAny(text, "甜品", "蛋糕", "甜点", "dessert", "冰淇淋", "布丁")) {
            variant = "restaurant_dessert";
        } else if (containsAny(text, "小吃", "夜市", "street", "snack", "包", "饼")) {
            variant = "restaurant_snack";
        } else if (containsAny(text, "饭", "蓋飯", "盖饭", "炒饭", "rice", "丼", "咖喱")) {
            variant = "restaurant_rice";
        }
        return "/static/place_images/" + variant + ".svg";
    }

    private static boolean containsAny(String text, String... markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 就地补图。{@code skipCategories} 里的类别（酒店）直接给占位图，不发请求。
     */
    public List<Map<String, Object>> resolve(
            List<Map<String, Object>> places, String destination, Set<String> skipCategories, int maxLookups) {
        int lookups = 0;
        for (Map<String, Object> place : places) {
            String category = String.valueOf(place.getOrDefault("type", place.getOrDefault("category", "attraction")));
            String name = String.valueOf(place.getOrDefault("name", ""));
            String dish = text(place.get("signature_dish"));
            String existing = text(place.get("image_url"));
            String fallback = fallbackImage(category, name, dish);
            if (existing != null) {
                // 已经有图（包括 /static 本地图）就不再搜：重新搜会把用户已保存的本地图
                // 换成一张不相干的库存照。
                place.putIfAbsent("fallback_image_url", fallback);
                continue;
            }
            if (skipCategories.contains(category) || name.isBlank() || lookups >= maxLookups) {
                place.put("image_url", null);
                place.put("fallback_image_url", fallback);
                continue;
            }
            lookups++;
            String image = null;
            String credit = null;
            String creditUrl = null;
            if ("restaurant".equals(category)) {
                MealDbClient.MealImage meal = mealDbClient.search(
                        dish == null || dish.isBlank() ? name : dish);
                if (meal != null && probe(meal.imageUrl())) {
                    image = meal.imageUrl();
                    credit = meal.credit();
                    creditUrl = meal.creditUrl();
                }
            }
            if (image == null && !pexelsApiKey.isBlank() && OUTDOOR_HINTS.contains(category)) {
                List<Map<String, String>> candidates = searchPexels(name, category, destination);
                for (int index = 0; index < Math.min(PROBE_CANDIDATES, candidates.size()); index++) {
                    Map<String, String> candidate = candidates.get(index);
                    if (probe(candidate.get("url"))) {
                        image = candidate.get("url");
                        credit = candidate.get("credit");
                        creditUrl = candidate.get("creditUrl");
                        break;
                    }
                }
            }
            place.put("image_url", image);
            place.put("fallback_image_url", fallback);
            if (credit != null) {
                place.put("image_credit", credit);
                place.put("image_credit_url", creditUrl);
            }
        }
        return places;
    }

    private List<Map<String, String>> searchPexels(String name, String category, String destination) {
        List<Map<String, String>> photos = new ArrayList<>();
        try {
            String query = name + " " + destination + " " + ("restaurant".equals(category) ? "food" : "landscape");
            String url = "https://api.pexels.com/v1/search?query="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&per_page=15&orientation=landscape";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(SEARCH_TIMEOUT)
                    .header("Authorization", pexelsApiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return photos;
            }
            JsonNode root = MAPPER.readTree(response.body());
            for (JsonNode photo : root.path("photos")) {
                String best = null;
                for (String key : List.of("large", "landscape", "medium", "small")) {
                    JsonNode src = photo.path("src").path(key);
                    if (src.isTextual() && !src.asText().isBlank()) {
                        best = src.asText();
                        break;
                    }
                }
                if (best == null) {
                    continue;
                }
                Map<String, String> candidate = new java.util.LinkedHashMap<>();
                candidate.put("url", best);
                String photographer = photo.path("photographer").asText("");
                candidate.put("credit", photographer.isBlank() ? null : "Photos provided by Pexels / " + photographer);
                candidate.put("creditUrl", photo.path("url").asText("https://www.pexels.com"));
                photos.add(candidate);
            }
        } catch (Exception error) {
            log.debug("pexels search failed for {}: {}", name, error.toString());
        }
        return photos;
    }

    /** 真的去拿前 64KB：只有真正连得上，浏览器才可能显示。 */
    private boolean probe(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        Boolean cached = probeCache.get(url);
        if (cached != null) {
            return cached;
        }
        boolean ok;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(PROBE_TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (compatible; travel-agent-platform/1.0)")
                    .header("Range", "bytes=0-65535")
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("content-type").orElse("");
            ok = response.statusCode() / 100 == 2 && contentType.startsWith("image/");
        } catch (Exception error) {
            ok = false;
        }
        probeCache.put(url, ok);
        return ok;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }
}
