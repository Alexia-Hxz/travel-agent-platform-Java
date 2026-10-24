package com.huxizhen.travel.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.config.DashScopeProperties;
import com.huxizhen.travel.integration.llm.DashScopeClient;
import com.huxizhen.travel.integration.search.WebSearchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 「只排一天」与「找几个新地点」的素材生成。
 *
 * <p>两件事都不写库：返回的是候选地点，调用方把它们变成草案操作，用户勾选后才落库。
 * 与 Python 的 {@code plan_one_day} / {@code plan_discovery} 对应：先联网检索，再让模型
 * 只输出 JSON，最后按目的地价格带定价。
 */
@Service
public class DayPlanner {

    private static final Logger log = LoggerFactory.getLogger(DayPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private static final String DAY_SYSTEM_PROMPT = """
            你是旅行规划师。为指定目的地安排**一天**：2 个景点、3 家餐厅（早餐/午餐/晚餐各一），
            地点必须来自检索素材、真实存在，且不能与"已用地点"重复（名字互相包含也算重复）。
            只输出 JSON 数组，不要解释、不要 Markdown 代码块。每个元素包含：
            name（中文名，必填）、type（attraction/restaurant）、reason（一句理由）、
            price_hint（素材里的价格，没有就 null）、unit_price（数字，人民币，没有就 null）、
            price_unit（person_meal/ticket）、address（地址，没有就目的地）、price_level（0-5）、
            meal_slot（breakfast/lunch/dinner/any）、signature_dish（招牌菜，没有就 null）。""";

    private static final String DISCOVERY_SYSTEM_PROMPT = """
            你是旅行规划师。根据旅行者的要求，从检索素材里挑出 5 个**新**地点（景点或餐厅），
            不能与"已用地点"重复（名字互相包含也算重复）。只输出 JSON 数组，不要解释、
            不要 Markdown 代码块。每个元素包含：name（中文名，必填）、type（attraction/restaurant）、
            reason（推荐理由）、price_hint（素材里的价格，没有就 null）、unit_price（数字，人民币，
            没有就 null）、price_unit（person_meal/ticket）、address（地址）、price_level（0-5）、
            meal_slot（breakfast/lunch/dinner/any）、signature_dish（招牌菜，没有就 null）。""";

    private final WebSearchService webSearchService;
    private final DashScopeClient client;
    private final DashScopeProperties properties;

    public DayPlanner(
            WebSearchService webSearchService, DashScopeClient client, DashScopeProperties properties) {
        this.webSearchService = webSearchService;
        this.client = client;
        this.properties = properties;
    }

    /** 一天的建议地点（未去重、未排时段；调用方按早/午/晚与上下午分配）。 */
    public List<Map<String, Object>> planOneDay(
            String destination,
            int tripDays,
            List<String> excludedNames,
            Map<String, Object> travelerProfile,
            Integer budget) {
        String material = material(destination, destination + " 一日游 景点 餐厅 美食 推荐 开放时间");
        return extract(
                DAY_SYSTEM_PROMPT,
                destination,
                "请为这一天挑 2 个景点和 3 家餐厅。已用地点：" + joinNames(excludedNames),
                material,
                Map.of("attraction", 6, "restaurant", 6),
                tripDays,
                travelerProfile,
                budget,
                excludedNames);
    }

    /** 「帮我找几个适合亲子的地方」的新地点候选。 */
    public List<Map<String, Object>> discover(
            String destination,
            String instruction,
            List<String> excludedNames,
            int tripDays,
            Map<String, Object> travelerProfile,
            Integer budget) {
        String material = material(destination, destination + " " + instruction + " 推荐 地点 餐厅 景点 价格");
        return extract(
                DISCOVERY_SYSTEM_PROMPT,
                destination,
                "旅行者的要求：" + instruction + "\n已用地点：" + joinNames(excludedNames),
                material,
                Map.of("attraction", 5, "restaurant", 5),
                tripDays,
                travelerProfile,
                budget,
                excludedNames);
    }

    private List<Map<String, Object>> extract(
            String systemPrompt,
            String destination,
            String requirement,
            String material,
            Map<String, Integer> limits,
            int tripDays,
            Map<String, Object> travelerProfile,
            Integer budget,
            List<String> excludedNames) {
        if (material.isBlank() || !client.configured()) {
            return List.of();
        }
        StringBuilder user = new StringBuilder();
        user.append("目的地：").append(destination).append("\n");
        user.append(CurrencyTool.promptHint(destination)).append("\n");
        user.append(requirement).append("\n");
        user.append("检索素材：\n").append(material);
        try {
            String content = client.complete(
                    DashScopeClient.textBody(
                            properties.planningModel(), systemPrompt, user.toString(), true, null, 0.3),
                    TIMEOUT);
            List<Map<String, Object>> items = parse(content);
            List<Map<String, Object>> places =
                    RecommendationService.normalize(items, limits, destination);
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> place : places) {
                if (!isExcluded(String.valueOf(place.get("name")), excludedNames)) {
                    filtered.add(place);
                }
            }
            RecommendationService.estimateCosts(filtered, tripDays, travelerProfile, budget, destination);
            return filtered;
        } catch (RuntimeException error) {
            log.warn("day planner extraction failed: {}", error.toString());
            return List.of();
        }
    }

    private String material(String destination, String query) {
        try {
            WebSearchService.SearchResult result = webSearchService.searchWeb(query, destination);
            if (!"completed".equals(result.status())) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (Map<String, Object> item : result.results()) {
                Object snippet = item.get("snippet");
                if (snippet == null) {
                    continue;
                }
                String text = String.valueOf(snippet).strip();
                if (!text.isEmpty()) {
                    builder.append(text, 0, Math.min(text.length(), 4000)).append("\n\n");
                }
            }
            return builder.toString().strip();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static List<Map<String, Object>> parse(String content) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return items;
        }
        try {
            JsonNode root = MAPPER.readTree(content.strip());
            JsonNode array = root.isArray() ? root : root.path("items");
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
        } catch (Exception error) {
            return items;
        }
        return items;
    }

    private static boolean isExcluded(String name, List<String> excluded) {
        if (name == null || name.isBlank()) {
            return true;
        }
        for (String other : excluded) {
            if (sameVenue(name, other)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameVenue(String first, String second) {
        String left = first == null ? "" : first.strip().toLowerCase(Locale.ROOT);
        String right = second == null ? "" : second.strip().toLowerCase(Locale.ROOT);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() <= right.length() ? right : left;
        if (!longer.contains(shorter)) {
            return false;
        }
        String remainder = longer.replaceFirst(
                java.util.regex.Pattern.quote(shorter), java.util.regex.Matcher.quoteReplacement(""));
        return !remainder.matches("\\d+");
    }

    private static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "（无）";
        }
        return String.join("、", names);
    }
}
