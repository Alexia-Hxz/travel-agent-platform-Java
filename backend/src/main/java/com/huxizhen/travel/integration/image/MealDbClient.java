package com.huxizhen.travel.integration.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TheMealDB 菜图库。
 *
 * <p>餐厅卡片与别的卡片不同：一张"这家店"的图没有意义，一张"这道菜"的图才是信息。
 * 所以餐厅优先按招牌菜（没有就按店名）去菜图库找，找不到才回落到 Pexels 和占位图。
 * 使用该图库必须署名并链接到主题站，署名信息随图片一起返回。
 */
@Component
public class MealDbClient {

    private static final Logger log = LoggerFactory.getLogger(MealDbClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEARCH_URL = "https://www.themealdb.com/api/json/v1/1/search.php?s=";
    private static final String ATTRIBUTION = "TheMealDB";
    private static final String ATTRIBUTION_URL = "https://www.themealdb.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 一道菜的图片与署名。 */
    public record MealImage(String imageUrl, String credit, String creditUrl) {
    }

    /** 按关键词找一张菜图；失败/无结果返回 null（调用方继续走 Pexels 或占位图）。 */
    public MealImage search(String term) {
        String query = englishTerm(term);
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            String url = SEARCH_URL + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "travel-agent-platform/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode meals = root.path("meals");
            if (!meals.isArray() || meals.isEmpty()) {
                return null;
            }
            List<String> images = new ArrayList<>();
            for (JsonNode meal : meals) {
                String image = meal.path("strMealThumb").asText("");
                if (!image.isBlank()) {
                    images.add(image);
                }
            }
            if (images.isEmpty()) {
                return null;
            }
            return new MealImage(images.get(0), ATTRIBUTION, ATTRIBUTION_URL);
        } catch (Exception error) {
            log.debug("mealdb lookup failed for {}: {}", term, error.toString());
            return null;
        }
    }

    /**
     * TheMealDB 按英文检索。中文菜名先映射到最常见的英文关键词；映射不到就放弃
     * （返回 null，交给 Pexels），不要拿整句中文去搜出一张不相干的图。
     */
    static String englishTerm(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        String text = term.strip();
        String[][] mappings = {
            {"寿司", "sushi"}, {"刺身", "sashimi"}, {"拉面", "ramen"}, {"乌冬", "udon"},
            {"烧肉", "bbq"}, {"烤肉", "bbq"}, {"火锅", "hotpot"}, {"海鲜", "seafood"},
            {"咖啡", "coffee"}, {"蛋糕", "cake"}, {"甜品", "dessert"}, {"披萨", "pizza"},
            {"汉堡", "burger"}, {"沙拉", "salad"}, {"鸡汤", "soup"}, {"牛肉", "beef"},
            {"鸡肉", "chicken"}, {"猪肉", "pork"}, {"鱼", "fish"}, {"面", "noodles"},
            {"饭", "rice"}, {"茶", "tea"}
        };
        for (String[] mapping : mappings) {
            if (text.contains(mapping[0])) {
                return mapping[1];
            }
        }
        if (text.chars().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
            return null;
        }
        return text;
    }
}
