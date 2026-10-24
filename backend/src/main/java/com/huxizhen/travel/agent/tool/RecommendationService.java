package com.huxizhen.travel.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.config.DashScopeProperties;
import com.huxizhen.travel.integration.llm.DashScopeClient;
import com.huxizhen.travel.integration.search.WebSearchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 从检索素材里抽出具体推荐地点，并给每个地点定价、补事实、铺价格档位。
 *
 * <p>整条链路与 Python 版 {@code agents/tools/recommendations.py} 对齐：
 * <ol>
 *   <li><b>抽取</b>：模型只输出 JSON，字段/类别/数量全部复核；</li>
 *   <li><b>分类补齐</b>：每类候选不足时按类别再检索、再抽取，最多 3 次；</li>
 *   <li><b>比价</b>：没有报价的地点用联网检索补一个参考价（8 并发，12s 封顶）；</li>
 *   <li><b>事实</b>：酒店评分/房价、餐厅招牌菜一次批量补全；</li>
 *   <li><b>定价</b>：报价优先，否则按目的地价格带给默认价；外币换算、当地下限抬升、
 *       免费识别、超预算标记、价格档位。</li>
 * </ol>
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration EXTRACTION_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration CATEGORY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration PRICE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration FACTS_TIMEOUT = Duration.ofSeconds(45);

    private static final ExecutorService PRICE_EXECUTOR = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "price-lookup");
        thread.setDaemon(true);
        return thread;
    });

    private static final List<String> CATEGORIES = List.of("hotel", "restaurant", "attraction");
    private static final Map<String, Integer> DEFAULT_UNIT_PRICES =
            Map.of("hotel", 400, "restaurant", 80, "attraction", 100);
    private static final Map<String, String> DEFAULT_PRICE_UNITS =
            Map.of("hotel", "night", "restaurant", "person_meal", "attraction", "ticket");
    private static final Map<String, Integer> DEFAULT_PRICE_LEVELS =
            Map.of("hotel", 3, "restaurant", 2, "attraction", 1);
    private static final double HOTEL_SHARE = 0.40;
    private static final double MEAL_SHARE = 0.30;
    private static final double TICKET_SHARE = 0.20;
    private static final int MEALS_PER_DAY = 3;
    private static final int MAX_ATTRACTIONS = 18;
    private static final int MAX_RESTAURANTS = 27;
    private static final int MAX_CATEGORY_FILLS = 3;

    private static final Map<String, String> CATEGORY_LABELS =
            Map.of("hotel", "酒店", "restaurant", "餐厅", "attraction", "景点");
    private static final Map<String, String> CATEGORY_QUERIES = Map.of(
            "hotel", "酒店 推荐 房价 每晚",
            "restaurant", "餐厅 美食 推荐 人均消费",
            "attraction", "景点 门票 推荐 开放时间");

    /** 分类补齐时逐轮换角度，避免三次都搜到同一批最知名的地点。 */
    private static final Map<String, String[]> CATEGORY_HINTS = Map.of(
            "hotel", new String[] {"位置方便 评分高", "本地人推荐 性价比", "亲子 交通便利"},
            "restaurant", new String[] {"本地人常去 特色", "小吃 老字号 平价", "招牌菜 高评分"},
            "attraction", new String[] {"必去 经典", "小众 新开 展馆", "亲子 室内 雨天"});

    private static final String SYSTEM_PROMPT = """
            你是旅行规划师。给你一段关于某个目的地的检索素材，请抽取出**真实存在**的
            酒店、餐厅、景点，只输出 JSON 数组，不要任何解释、不要 Markdown 代码块。
            每个元素包含：
            name（中文名，必填）、category（hotel/restaurant/attraction）、
            reason（一句推荐理由）、price_hint（素材原文里的价格，没有就 null）、
            unit_price（数字，人民币，没有就 null）、price_unit（night/person_meal/ticket）、
            latitude、longitude（估个大概，没有就 null）、
            signature_dish（餐厅招牌菜，没有就 null）、meal_slot（breakfast/lunch/dinner/any）、
            price_level（0-5，没有就 null）。
            名称必须是素材里出现过的专有名词，不要编造；不要用"推荐酒店1"这类占位名。""";

    private static final String CATEGORY_SYSTEM_PROMPT = """
            你是旅行规划师。根据给出的检索素材，只抽取指定类别的真实地点，只输出 JSON 数组，
            不要解释、不要 Markdown 代码块。每个元素包含：
            name（中文名，必填）、category（必须等于指定类别）、reason（一句推荐理由）、
            price_hint（素材原文里的价格，没有就 null）、unit_price（数字，人民币，没有就 null）、
            price_unit（night/person_meal/ticket）、latitude、longitude（没有就 null）、
            signature_dish（餐厅招牌菜，没有就 null）、meal_slot（breakfast/lunch/dinner/any）、
            price_level（0-5，没有就 null）。
            名称必须是素材里出现过的专有名词；数量不足就少给，禁止编造。""";

    private static final String PRICE_SYSTEM_PROMPT = """
            你是旅行成本分析员。下面是若干地点和检索到的价格资料，请为每个地点给出一个
            参考单价（整数人民币元）。只输出 JSON 对象，形如 {"地点名称": 数字}。
            hotel 填每晚房价；restaurant 填每人每餐人均；attraction 填每人门票。
            资料里的价格若是外币，必须换算成人民币整数再填；不要直接照抄外币数字。
            资料完全没有价格线索时给 null，禁止凭空编造。只输出输入中出现过的地点名称。""";

    private static final String FACTS_SYSTEM_PROMPT = """
            你是旅行资料核验员。下面是地点列表（名称、类别、已有价格线索）和目的地。
            请为每个地点补全：酒店给 rating（0-5）与 unit_price（每晚房价，人民币整数）；
            餐厅给 signature_dish（招牌菜，最多 14 个字）；景点可不补。
            只输出 JSON 对象：{"地点名称": {"rating": 数字或 null, "unit_price": 数字或 null,
            "signature_dish": "字符串或 null"}}。没有把握就填 null，禁止编造。""";

    private final DashScopeClient client;
    private final DashScopeProperties properties;
    private final WebSearchService webSearchService;

    public RecommendationService(
            DashScopeClient client, DashScopeProperties properties, WebSearchService webSearchService) {
        this.client = client;
        this.properties = properties;
        this.webSearchService = webSearchService;
    }

    /** 分类卡片回调：补齐过程中每完成一类就推一张卡片。 */
    public interface CategoryPublisher {
        void publish(String category, List<Map<String, Object>> entries);
    }

    /** 预算上限：一类"一个单位"最多能花多少还装得进预算。没有预算就返回空表。 */
    public static Map<String, Integer> budgetAllowances(
            Integer tripBudget, int tripDays, Map<String, Object> travelerProfile) {
        if (tripBudget == null || tripBudget <= 0) {
            return Map.of();
        }
        int days = Math.max(1, tripDays);
        int nights = Math.max(1, days - 1);
        int people = Math.max(1, peopleOf(travelerProfile));
        Map<String, Integer> allowances = new LinkedHashMap<>();
        allowances.put("hotel", Math.max(1, (int) (tripBudget * HOTEL_SHARE / nights)));
        allowances.put("restaurant", Math.max(1, (int) (tripBudget * MEAL_SHARE / (days * MEALS_PER_DAY * people))));
        allowances.put("attraction", Math.max(1, (int) (tripBudget * TICKET_SHARE / people)));
        return allowances;
    }

    public static Map<String, Integer> categoryLimits(int tripDays) {
        int days = Math.max(1, tripDays);
        Map<String, Integer> limits = new LinkedHashMap<>();
        limits.put("hotel", 3);
        limits.put("restaurant", Math.min(MAX_RESTAURANTS, days * MEALS_PER_DAY + 6));
        limits.put("attraction", Math.min(MAX_ATTRACTIONS, days * 2 + 4));
        return limits;
    }

    /** 抽取结果。{@code status} 会进事件日志（completed / fallback / skipped）。 */
    public record Extraction(List<Map<String, Object>> places, String status) {
    }

    public Extraction extract(
            String destination,
            List<Map<String, Object>> webResults,
            Map<String, Integer> allowances,
            Map<String, Integer> limits) {
        String material = materialText(webResults);
        if (material.isBlank()) {
            return new Extraction(List.of(), "skipped");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.planningModel());
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt(destination, material, allowances, limits))));
        body.put("response_format", Map.of("type", "json_object"));
        body.put("temperature", 0);

        try {
            String content = client.complete(body, EXTRACTION_TIMEOUT);
            List<Map<String, Object>> items = parseItems(content);
            if (items.isEmpty()) {
                return new Extraction(fallbackItems(material, destination), "fallback");
            }
            return new Extraction(normalize(items, limits, destination), "completed");
        } catch (RuntimeException error) {
            log.warn("recommendation extraction failed: {}", error.toString());
            return new Extraction(fallbackItems(material, destination), "fallback");
        }
    }

    /**
     * 每类候选不足时补搜补抽。数量按 {@code category_limits} 走，最多 3 轮；
     * 每补完一类就通过 {@code publisher} 推一张分类卡片（与 Python 的 on_category_ready 一致）。
     */
    public List<Map<String, Object>> ensureCategoryCoverage(
            String destination,
            List<Map<String, Object>> current,
            Map<String, Integer> allowances,
            Map<String, Integer> limits,
            CategoryPublisher publisher) {
        List<Map<String, Object>> places = new ArrayList<>(current);
        for (String category : CATEGORIES) {
            int desired = limits.getOrDefault(category, 0);
            for (int attempt = 0; attempt < MAX_CATEGORY_FILLS; attempt++) {
                int shortfall = desired - countCategory(places, category);
                if (shortfall <= 0) {
                    break;
                }
                String[] hints = CATEGORY_HINTS.getOrDefault(category, new String[] {""});
                String hint = hints[Math.min(attempt, hints.length - 1)];
                List<Map<String, Object>> found =
                        fillCategory(destination, category, Math.min(shortfall, 6), allowances, hint);
                int added = mergeNew(places, found, category);
                if (added == 0) {
                    // 第一轮就一无所获时继续换词重试；第二轮仍为空说明这类素材确实不足。
                    if (attempt >= 1) {
                        break;
                    }
                    continue;
                }
                if (publisher != null) {
                    publisher.publish(category, entriesOfCategory(places, category));
                }
            }
        }
        return places;
    }

    /**
     * 没有报价的地点补一个参考价。8 并发检索，12s 封顶；模型再一次性把资料读成数字。
     * 任何一步失败都保持"没有报价"，绝不用编造的数字填。
     */
    public void enrichPricesWithSearch(List<Map<String, Object>> places, String destination) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> place : places) {
            if (intOrNull(place.get("unit_price")) == null
                    && !PriceGuard.isFree(text(place.get("name")), text(place.get("price_hint")))) {
                targets.add(place);
                if (targets.size() >= 8) {
                    break;
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        Map<String, String> snippets = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map<String, Object> place : targets) {
            String name = text(place.get("name"));
            if (name.isEmpty()) {
                continue;
            }
            String query = priceQuery(name, text(place.get("type")), destination);
            futures.add(CompletableFuture.runAsync(() -> {
                String snippet = firstSnippet(query);
                if (snippet != null && !snippet.isBlank()) {
                    snippets.put(name, snippet);
                }
            }, PRICE_EXECUTOR));
        }
        for (CompletableFuture<Void> future : futures) {
            try {
                future.get(12, TimeUnit.SECONDS);
            } catch (Exception error) {
                // 这一条比价超时/失败：保持没有报价，不影响整趟规划
            }
        }
        if (snippets.isEmpty()) {
            return;
        }
        Map<String, Integer> prices = askPriceModel(snippets, destination);
        if (prices.isEmpty()) {
            return;
        }
        for (Map<String, Object> place : places) {
            String name = text(place.get("name"));
            Integer price = prices.get(name);
            if (price == null) {
                continue;
            }
            Integer bounded = PriceGuard.plausible(price, text(place.get("type")), destination);
            if (bounded != null) {
                place.put("unit_price", bounded);
                if (place.get("price_hint") == null) {
                    place.put("price_hint", "联网参考价");
                }
            }
        }
    }

    /** 酒店评分/房价、餐厅招牌菜的一次批量补全。失败就保持原样。 */
    public void enrichPlaceFacts(List<Map<String, Object>> places, String destination) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> place : places) {
            String category = text(place.get("type"));
            boolean needsFacts = ("hotel".equals(category)
                            && (place.get("rating") == null || place.get("unit_price") == null))
                    || ("restaurant".equals(category) && text(place.get("signature_dish")).isEmpty());
            if (needsFacts) {
                targets.add(place);
                if (targets.size() >= 12) {
                    break;
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        List<Map<String, Object>> brief = new ArrayList<>();
        for (Map<String, Object> place : targets) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", text(place.get("name")));
            entry.put("type", text(place.get("type")));
            entry.put("price_hint", text(place.get("price_hint")));
            entry.put("unit_price", intOrNull(place.get("unit_price")));
            brief.add(entry);
        }
        try {
            String material = MAPPER.writeValueAsString(brief);
            String user = "目的地：" + destination + "\n地点列表：" + material;
            String content = client.complete(
                    DashScopeClient.textBody(
                            properties.planningModel(), FACTS_SYSTEM_PROMPT, user, true, null, 0.2),
                    FACTS_TIMEOUT);
            JsonNode root = MAPPER.readTree(content == null ? "{}" : content.strip());
            for (Map<String, Object> place : targets) {
                JsonNode facts = root.path(text(place.get("name")));
                if (!facts.isObject()) {
                    continue;
                }
                if (facts.hasNonNull("rating")) {
                    double rating = facts.get("rating").asDouble();
                    if (rating >= 0 && rating <= 5) {
                        place.put("rating", rating);
                    }
                }
                if (facts.hasNonNull("unit_price")) {
                    Integer price = PriceGuard.plausible(
                            facts.get("unit_price").asInt(), text(place.get("type")), destination);
                    if (price != null) {
                        place.put("unit_price", price);
                    }
                }
                if (facts.hasNonNull("signature_dish")) {
                    String dish = facts.get("signature_dish").asText("").strip();
                    if (!dish.isEmpty()) {
                        place.put("signature_dish", dish.length() > 14 ? dish.substring(0, 14) : dish);
                    }
                }
            }
        } catch (Exception error) {
            log.warn("place facts enrichment failed: {}", error.toString());
        }
    }

    /**
     * 价格档位铺开：预算要求"能天天吃"和"值得一次"的档位都存在。缺哪档就补搜哪档。
     *
     * @return 新补进来的地点（调用方需要再跑一次 {@link #estimateCosts}）
     */
    public List<Map<String, Object>> ensurePriceSpread(
            String destination,
            List<Map<String, Object>> places,
            Integer tripBudget,
            int tripDays,
            Map<String, Object> travelerProfile) {
        if (tripBudget == null || tripBudget <= 0 || places.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> allowances = budgetAllowances(tripBudget, tripDays, travelerProfile);
        List<Map<String, Object>> added = new ArrayList<>();
        for (String category : List.of("restaurant", "hotel")) {
            int allowance = allowances.getOrDefault(category, 0);
            if (allowance <= 0) {
                continue;
            }
            boolean hasCheap = false;
            boolean hasSplurge = false;
            for (Map<String, Object> place : places) {
                if (!category.equals(text(place.get("type")))) {
                    continue;
                }
                Integer price = intOrNull(place.get("unit_price"));
                if (price == null) {
                    continue;
                }
                hasCheap = hasCheap || price <= allowance * 0.7;
                hasSplurge = hasSplurge || price >= allowance * 1.2;
            }
            if (!hasCheap) {
                List<Map<String, Object>> cheap =
                        fillCategory(destination, category, 2, allowances, "便宜 实惠 本地人常去");
                for (Map<String, Object> place : cheap) {
                    if (intOrNull(place.get("unit_price")) == null) {
                        place.put("unit_price", Math.max(1, (int) Math.round(allowance * 0.5)));
                        place.put("price_hint", "按预算档位估算");
                    }
                }
                added.addAll(cheap);
            }
            if (!hasSplurge) {
                List<Map<String, Object>> splurge =
                        fillCategory(destination, category, 1, allowances, "高档 特色 值得专程");
                for (Map<String, Object> place : splurge) {
                    if (intOrNull(place.get("unit_price")) == null) {
                        place.put("unit_price", Math.max(1, (int) Math.round(allowance * 1.6)));
                        place.put("price_hint", "按预算档位估算");
                    }
                }
                added.addAll(splurge);
            }
        }
        List<Map<String, Object>> appended = new ArrayList<>();
        for (Map<String, Object> place : added) {
            if (mergeNew(places, List.of(place), text(place.get("type"))) > 0) {
                appended.add(place);
            }
        }
        return appended;
    }

    private static String userPrompt(
            String destination, String material, Map<String, Integer> allowances, Map<String, Integer> limits) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("目的地：").append(destination).append("\n");
        prompt.append(CurrencyTool.promptHint(destination)).append("\n");
        prompt.append("素材：\n").append(material).append("\n\n");
        if (allowances != null && !allowances.isEmpty()) {
            prompt.append("预算上限参考（只是筛选参考，不是价格）：");
            List<String> parts = new ArrayList<>();
            if (allowances.containsKey("hotel")) {
                parts.add("酒店每晚不超过约 " + allowances.get("hotel") + " 元");
            }
            if (allowances.containsKey("restaurant")) {
                parts.add("每餐每人不超过约 " + allowances.get("restaurant") + " 元");
            }
            if (allowances.containsKey("attraction")) {
                parts.add("门票每人不超过约 " + allowances.get("attraction") + " 元");
            }
            prompt.append(String.join("；", parts)).append("。\n");
        }
        prompt.append("数量要求：酒店 ").append(limits.getOrDefault("hotel", 3))
                .append(" 个，餐厅 ").append(limits.getOrDefault("restaurant", 6))
                .append(" 个，景点 ").append(limits.getOrDefault("attraction", 4))
                .append(" 个。数量不足就按素材里能提取到的给。\n");
        prompt.append("输出 JSON 对象：{\"items\": [ ... ]}。");
        return prompt.toString();
    }

    /**
     * 针对一个类别再检索、再抽取。素材来自联网检索；模型返回后只保留该类别并去重。
     */
    private List<Map<String, Object>> fillCategory(
            String destination,
            String category,
            int count,
            Map<String, Integer> allowances,
            String hint) {
        if (count <= 0 || !client.configured()) {
            return List.of();
        }
        String query = destination + " " + CATEGORY_QUERIES.getOrDefault(category, "") + " " + (hint == null ? "" : hint);
        String material = "";
        try {
            WebSearchService.SearchResult result = webSearchService.searchWeb(query, destination);
            if ("completed".equals(result.status())) {
                material = materialText(result.results());
            }
        } catch (RuntimeException error) {
            log.warn("category search failed for {}: {}", category, error.toString());
        }
        if (material.isBlank()) {
            return List.of();
        }
        StringBuilder user = new StringBuilder();
        user.append("目的地：").append(destination).append("\n");
        user.append("指定类别：").append(category).append("（").append(CATEGORY_LABELS.get(category)).append("）\n");
        user.append("需要数量：").append(count).append("\n");
        user.append(CurrencyTool.promptHint(destination)).append("\n");
        Integer allowance = allowances == null ? null : allowances.get(category);
        if (allowance != null) {
            user.append("预算参考：本类一个单位不超过约 ").append(allowance).append(" 元。\n");
        }
        user.append("检索素材：\n").append(material);
        try {
            String content = client.complete(
                    DashScopeClient.textBody(
                            properties.planningModel(),
                            CATEGORY_SYSTEM_PROMPT,
                            user.toString(),
                            true,
                            null,
                            0.2),
                    CATEGORY_TIMEOUT);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> item : parseItems(content)) {
                if (category.equals(text(item.get("category")).toLowerCase(Locale.ROOT))
                        || category.equals(text(item.get("type")).toLowerCase(Locale.ROOT))) {
                    items.add(item);
                }
            }
            Map<String, Integer> limits = Map.of(category, Math.max(1, count));
            return normalize(items, limits, destination);
        } catch (RuntimeException error) {
            log.warn("category fill failed for {}: {}", category, error.toString());
            return List.of();
        }
    }

    private Map<String, Integer> askPriceModel(Map<String, String> snippets, String destination) {
        try {
            String material = MAPPER.writeValueAsString(snippets);
            String user = "目的地：" + destination + "\n" + CurrencyTool.promptHint(destination)
                    + "\n地点与资料：" + material;
            String content = client.complete(
                    DashScopeClient.textBody(
                            properties.planningModel(), PRICE_SYSTEM_PROMPT, user, true, null, 0.0),
                    PRICE_TIMEOUT);
            JsonNode root = MAPPER.readTree(content == null ? "{}" : content.strip());
            Map<String, Integer> prices = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && value.isNumber()) {
                    prices.put(entry.getKey(), value.asInt());
                } else if (value != null && value.isTextual()) {
                    Integer parsed = intOrNull(value.asText());
                    if (parsed != null) {
                        prices.put(entry.getKey(), parsed);
                    }
                }
            });
            return prices;
        } catch (Exception error) {
            log.warn("price model failed: {}", error.toString());
            return Map.of();
        }
    }

    private String firstSnippet(String query) {
        try {
            List<String> snippets = webSearchService.searchSnippets(query, 8);
            return snippets.isEmpty() ? null : snippets.get(0);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String priceQuery(String name, String category, String destination) {
        String hint = switch (category) {
            case "hotel" -> "房价 每晚 多少钱";
            case "restaurant" -> "人均消费 多少钱";
            default -> "门票价格 多少钱";
        };
        return name + " " + destination + " " + hint;
    }

    private static String materialText(List<Map<String, Object>> webResults) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> result : webResults) {
            Object snippet = result.get("snippet");
            if (snippet == null) {
                continue;
            }
            String text = String.valueOf(snippet).strip();
            if (text.isEmpty()) {
                continue;
            }
            builder.append(text, 0, Math.min(text.length(), 4000)).append("\n\n");
        }
        return builder.toString().strip();
    }

    private static List<Map<String, Object>> parseItems(String content) {
        try {
            String text = content == null ? "" : content.strip();
            JsonNode root = MAPPER.readTree(text);
            JsonNode array = root.isArray() ? root : root.path("items");
            List<Map<String, Object>> items = new ArrayList<>();
            if (!array.isArray()) {
                return items;
            }
            for (JsonNode node : array) {
                if (node.isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> converted = MAPPER.convertValue(node, Map.class);
                    items.add(converted);
                }
            }
            return items;
        } catch (Exception error) {
            return List.of();
        }
    }

    /** 复核并去重：名字必须有、类别必须合法、同类不重复、每类不超过上限。 */
    static List<Map<String, Object>> normalize(
            List<Map<String, Object>> items, Map<String, Integer> limits, String destination) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String name = text(item.get("name"));
            String category = text(item.get("category")).toLowerCase(Locale.ROOT);
            if (category.isEmpty()) {
                category = text(item.get("type")).toLowerCase(Locale.ROOT);
            }
            if (name.isEmpty() || name.length() > 160 || !CATEGORIES.contains(category)) {
                continue;
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            int limit = limits.getOrDefault(category, 99);
            int used = counts.getOrDefault(category, 0);
            if (used >= limit) {
                continue;
            }
            counts.put(category, used + 1);

            Map<String, Object> place = new LinkedHashMap<>();
            place.put("name", name);
            place.put("type", category);
            place.put("address", destination);
            place.put("reason", text(item.get("reason")));
            place.put("price_hint", text(item.get("price_hint")));
            Integer quoted = PriceGuard.plausible(intOrNull(item.get("unit_price")), category, destination);
            place.put("unit_price", quoted);
            String unit = text(item.get("price_unit")).toLowerCase(Locale.ROOT);
            place.put("price_unit", Set.of("night", "person", "person_meal", "ticket").contains(unit)
                    ? unit
                    : DEFAULT_PRICE_UNITS.get(category));
            place.put("price_level", priceLevel(item.get("price_level"), category));
            place.put("rating", doubleOrNull(item.get("rating"), 5));
            place.put("latitude", doubleOrNull(item.get("latitude"), 90));
            place.put("longitude", doubleOrNull(item.get("longitude"), 180));
            place.put("signature_dish", text(item.get("signature_dish")));
            place.put("meal_slot", mealSlot(text(item.get("meal_slot")), category, name));
            place.put("source_url", null);
            place.put("image_url", null);
            place.put("fallback_image_url", null);
            kept.add(place);
        }
        return kept;
    }

    private static Integer priceLevel(Object raw, String category) {
        Integer level = intOrNull(raw);
        if (level != null && level >= 0 && level <= 5) {
            return level;
        }
        return DEFAULT_PRICE_LEVELS.getOrDefault(category, 2);
    }

    /**
     * 模型不可用时的关键词兜底：从素材里按行/分句找"名称：说明"形状的专有名词，
     * 至少保证卡片不是一段 60 字的句子。宁可少给，也不编造。
     */
    private static List<Map<String, Object>> fallbackItems(String material, String destination) {
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String rawLine : material.replace("\r", "\n").split("[\n。；;]")) {
            String line = rawLine.strip().replaceAll("^[-*•\\d.、)）\\s]+", "");
            if (line.length() < 2 || line.length() > 160) {
                continue;
            }
            String candidate = line;
            int separator = firstSeparator(line);
            if (separator > 0) {
                candidate = line.substring(0, separator).strip();
            }
            if (candidate.length() < 2 || candidate.length() > 40) {
                continue;
            }
            if (candidate.chars().anyMatch(Character::isDigit) && candidate.replaceAll("[^0-9]", "").length() > 6) {
                continue;
            }
            if (candidate.contains("推荐") || candidate.contains("攻略") || candidate.contains("总结")
                    || candidate.contains("以上") || candidate.contains("注意")) {
                continue;
            }
            String category = classifyCategory(candidate + " " + line);
            int limit = "hotel".equals(category) ? 3 : ("restaurant".equals(category) ? 6 : 4);
            int used = counts.getOrDefault(category, 0);
            if (used >= limit || !seen.add(candidate.toLowerCase(Locale.ROOT))) {
                continue;
            }
            counts.put(category, used + 1);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", candidate);
            item.put("type", category);
            item.put("address", destination);
            item.put("reason", line.length() > 80 ? line.substring(0, 80) : line);
            item.put("unit_price", null);
            item.put("price_unit", DEFAULT_PRICE_UNITS.get(category));
            item.put("price_level", DEFAULT_PRICE_LEVELS.get(category));
            item.put("latitude", null);
            item.put("longitude", null);
            item.put("signature_dish", null);
            item.put("meal_slot", mealSlot("", category, candidate));
            items.add(item);
        }
        return items;
    }

    private static int firstSeparator(String line) {
        for (String separator : List.of("：", ":", "——", " - ", "—", "（", "(")) {
            int index = line.indexOf(separator);
            if (index > 0) {
                return index;
            }
        }
        return -1;
    }

    private static String classifyCategory(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (containsAny(value, "酒店", "民宿", "旅馆", "住宿", "hotel", "hostel", "度假村")) {
            return "hotel";
        }
        if (containsAny(value, "餐厅", "餐馆", "饭店", "美食", "小吃", "咖啡", "甜品", "菜", "餐",
                "拉面", "寿司", "火锅", "restaurant", "cafe")) {
            return "restaurant";
        }
        return "attraction";
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
     * 给每个地点算出"这趟旅行用它要花多少钱"。
     *
     * <p>报价优先；没有报价按目的地价格带的典型价；低于当地下限会抬到下限并标记；
     * 资料说免费则归零。数量默认 1，用户可以在卡片上改，预算跟着变。
     */
    public static void estimateCosts(
            List<Map<String, Object>> places,
            int tripDays,
            Map<String, Object> travelerProfile,
            Integer tripBudget,
            String destination) {
        int days = Math.max(1, tripDays);
        int nights = Math.max(1, days - 1);
        int people = Math.max(1, peopleOf(travelerProfile));
        Map<String, Integer> allowances = budgetAllowances(tripBudget, days, travelerProfile);
        for (Map<String, Object> place : places) {
            String category = text(place.get("type"));
            if (!CATEGORIES.contains(category)) {
                continue;
            }
            String name = text(place.get("name"));
            String dish = text(place.get("signature_dish"));
            String priceHint = text(place.get("price_hint"));
            boolean free = PriceGuard.isFree(name, priceHint) || Boolean.TRUE.equals(place.get("is_free"));
            Integer quoted = free ? Integer.valueOf(0)
                    : PriceGuard.plausible(intOrNull(place.get("unit_price")), category, destination);
            boolean fromDefault = !free && quoted == null;
            int unitPrice = free ? 0
                    : (fromDefault
                            ? PriceGuard.defaultPrice(destination, category, name, dish, priceHint)
                            : quoted);
            boolean adjusted = false;
            String adjustedNote = null;
            if (!free) {
                PriceGuard.Adjusted guarded = PriceGuard.adjust(
                        unitPrice, category, destination, name, dish, priceHint);
                unitPrice = guarded.price();
                adjusted = guarded.adjusted();
                adjustedNote = guarded.note();
            }

            String unit = switch (category) {
                case "hotel" -> "night";
                case "restaurant" -> "person_meal";
                default -> "ticket";
            };
            int quantity = 1;
            String quantityUnit = "hotel".equals(category) ? "晚" : ("restaurant".equals(category) ? "次" : null);
            int amount;
            String formula;
            if ("night".equals(unit) || "hotel".equals(category)) {
                amount = unitPrice * quantity;
                formula = "约 " + unitPrice + " 元/晚 × " + quantity + " 晚 = " + amount + " 元";
            } else if ("person_meal".equals(unit) || "restaurant".equals(category)) {
                amount = unitPrice * people * quantity;
                formula = "约 " + unitPrice + " 元/人/餐 × " + people + " 人 × " + quantity + " 次 = " + amount + " 元";
            } else {
                amount = unitPrice * people;
                formula = "约 " + unitPrice + " 元/人 × " + people + " 人 = " + amount + " 元";
            }

            String source;
            if (free || amount == 0) {
                source = "资料显示免费";
            } else if (adjusted && adjustedNote != null) {
                source = adjustedNote;
            } else if (fromDefault) {
                source = "按当地中档常见价估算";
            } else {
                source = "按检索到的价格估算";
            }

            place.put("unit_price", unitPrice);
            place.put("price_unit", unit);
            place.put("quantity", quantity);
            place.put("quantity_unit", quantityUnit);
            place.put("quantity_max", "hotel".equals(category) ? nights : days);
            place.put("estimated_cost", amount);
            place.put("cost_explanation", formula + "（" + source + "）");
            place.put("cost_source", source);
            place.put("price_adjusted", adjusted);
            place.put("is_free", amount == 0);
            place.put("price_pending", false);

            Integer allowance = allowances.get(category);
            if (allowance == null) {
                place.put("over_budget", false);
                place.put("budget_limit", null);
                place.put("budget_note", null);
                place.put("price_tier", "");
                place.put("price_tier_label", "");
                continue;
            }
            int ceiling = PriceGuard.overBudgetCeiling(category, allowance);
            boolean over = unitPrice > ceiling;
            boolean splurge = "restaurant".equals(category) && unitPrice > allowance * 1.3;
            String tier = PriceGuard.tierOf(unitPrice, allowance);
            place.put("over_budget", over);
            place.put("budget_limit", allowance);
            place.put("price_tier", tier);
            place.put("price_tier_label", PriceGuard.tierLabel(tier));
            if (splurge && !over) {
                place.put("budget_note",
                        "比人均参考（" + allowance + " 元）高一些，属于按你的预算留出的“值得多花钱”那一档");
            } else if (over) {
                place.put("budget_note", "hotel".equals(category)
                        ? "超出预算参考（住宿约 " + allowance + " 元/晚 以内更合适）"
                        : "超出预算参考（建议单笔不超过 " + ceiling + " 元）");
            } else {
                place.put("budget_note", "");
            }
        }
        // 负担得起的排前面，组内保持模型原本的顺序（稳定排序）。
        places.sort(Comparator.comparing(
                (Map<String, Object> place) -> Boolean.TRUE.equals(place.get("over_budget"))));
    }

    private static int countCategory(List<Map<String, Object>> places, String category) {
        int count = 0;
        for (Map<String, Object> place : places) {
            if (category.equals(text(place.get("type")))) {
                count++;
            }
        }
        return count;
    }

    private static List<Map<String, Object>> entriesOfCategory(
            List<Map<String, Object>> places, String category) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> place : places) {
            if (category.equals(text(place.get("type")))) {
                entries.add(place);
            }
        }
        return entries;
    }

    /** 合并新地点（按名称去重），返回实际加入的数量。 */
    private static int mergeNew(
            List<Map<String, Object>> places, List<Map<String, Object>> found, String category) {
        Set<String> existing = new LinkedHashSet<>();
        for (Map<String, Object> place : places) {
            existing.add(text(place.get("name")).toLowerCase(Locale.ROOT));
        }
        int added = 0;
        for (Map<String, Object> place : found) {
            if (!category.equals(text(place.get("type")))) {
                continue;
            }
            String name = text(place.get("name"));
            if (name.isEmpty() || !existing.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            places.add(place);
            added++;
        }
        return added;
    }

    private static int peopleOf(Map<String, Object> travelerProfile) {
        if (travelerProfile == null) {
            return 1;
        }
        Object value = travelerProfile.get("total_people");
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(1, (int) Math.round(Double.parseDouble(String.valueOf(value))));
        } catch (RuntimeException error) {
            return 1;
        }
    }

    static String mealSlot(String declared, String category, String name) {
        if (!"restaurant".equals(category)) {
            return null;
        }
        String slot = declared.toLowerCase(Locale.ROOT);
        if (List.of("breakfast", "lunch", "dinner", "any").contains(slot)) {
            return slot;
        }
        if (name.contains("早餐") || name.contains("包子") || name.contains("粥")) {
            return "breakfast";
        }
        return "any";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                return (int) Math.round(number.doubleValue());
            }
            String text = String.valueOf(value).replaceAll("[^0-9.\\-]", "");
            return text.isEmpty() ? null : (int) Math.round(Double.parseDouble(text));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Double doubleOrNull(Object value, double limit) {
        if (value == null) {
            return null;
        }
        try {
            double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
            return Math.abs(number) > limit ? null : number;
        } catch (RuntimeException error) {
            return null;
        }
    }
}
