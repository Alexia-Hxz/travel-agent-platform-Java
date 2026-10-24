package com.huxizhen.travel.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 预算估算，逐行对齐 Python 的 {@code app/agents/tools/budget.py}。
 *
 * <p>两个模型回答不同的问题：
 * <ul>
 *   <li>{@link #estimateBudget} 给"收藏地点"估价（规划面板用）；</li>
 *   <li>{@link #estimateItineraryBudget} 给"已排进每日行程的项目"逐项估价（行程编辑器用），
 *       删掉一个行程项，它的钱就自动从总额里消失。</li>
 * </ul>
 *
 * <p>带报价（{@code quotedCost}）的地点按报价计费——那是用户看到并同意的数字，
 * 任何按价格等级推算的公式都不该覆盖它。
 */
public final class BudgetTool {

    private BudgetTool() {
    }

    static final Map<String, Integer> LEVEL_UNIT_PRICE =
            Map.of("hotel", 260, "restaurant", 80, "airport", 30, "station", 30);
    static final Map<String, Integer> DEFAULT_LEVELS =
            Map.of("hotel", 3, "restaurant", 2, "airport", 1, "station", 1, "attraction", 1);
    static final int DAILY_TRANSPORT = 60;
    static final int REFERENCE_HOTEL_PER_NIGHT = 300;
    static final int REFERENCE_MEAL_PER_PERSON_PER_DAY = 80;

    /** 预算结果。{@code breakdown} 的每条都带 {@code name/amount/...}。 */
    public record Estimate(
            int totalEstimate,
            Integer tripBudget,
            Integer remainingBudget,
            String currency,
            String note,
            List<Map<String, Object>> breakdown,
            int dailyEstimate,
            String itemizedSummary,
            List<Map<String, Object>> excludedAllowances) {
    }

    public static String formatItemizedSummary(List<Map<String, Object>> breakdown, int total) {
        String currencyLabel = "元";
        List<Map<String, Object>> lines = breakdown == null || breakdown.isEmpty()
                ? List.of(Map.of("name", "基础支出", "amount", total))
                : breakdown;
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            String name = String.valueOf(line.getOrDefault("name", "项目"));
            int amount = toInt(line.get("amount"), 0);
            String value = amount <= 0 ? "免费" : amount + currencyLabel;
            parts.add(name + "（" + value + "）");
        }
        return String.join(" + ", parts) + " = " + total + currencyLabel;
    }

    /** 用户看到过的报价：收藏地点叫 quoted_cost，推荐卡片叫 estimated_cost。 */
    static Integer quotedCost(Map<String, Object> place) {
        for (String key : List.of("quotedCost", "quoted_cost", "estimatedCost", "estimated_cost")) {
            Object raw = place.get(key);
            if (raw == null) {
                continue;
            }
            try {
                return Math.max(0, toInt(raw, 0));
            } catch (RuntimeException ignored) {
                // 换个键继续
            }
        }
        return null;
    }

    static int[] levelOf(Map<String, Object> place) {
        Object raw = place.get("priceLevel");
        if (raw == null) {
            raw = place.get("price_level");
        }
        if (raw == null) {
            return new int[] {DEFAULT_LEVELS.getOrDefault(typeOf(place), 2), 1};
        }
        try {
            return new int[] {Math.max(0, Math.min(5, toInt(raw, 0))), 0};
        } catch (RuntimeException error) {
            return new int[] {DEFAULT_LEVELS.getOrDefault(typeOf(place), 2), 1};
        }
    }

    static String typeOf(Map<String, Object> place) {
        Object type = place.get("type");
        if (type == null) {
            type = place.get("category");
        }
        return type == null ? "" : String.valueOf(type);
    }

    static int quantityOf(Map<String, Object> place) {
        Object raw = place.get("quantity");
        if (raw == null) {
            return 1;
        }
        try {
            return Math.max(1, Math.min(99, toInt(raw, 1)));
        } catch (RuntimeException error) {
            return 1;
        }
    }

    static int levelAmount(String type, int level, int days, int people) {
        return switch (type) {
            case "hotel" -> level * LEVEL_UNIT_PRICE.get("hotel") * Math.max(1, days - 1);
            case "restaurant" -> level * LEVEL_UNIT_PRICE.get("restaurant") * days * people;
            case "airport", "station" -> level * LEVEL_UNIT_PRICE.get("airport");
            default -> level * 100;
        };
    }

    static int levelUnitPrice(String type, int level, int people) {
        return switch (type) {
            case "hotel" -> level * LEVEL_UNIT_PRICE.get("hotel");
            case "restaurant" -> level * LEVEL_UNIT_PRICE.get("restaurant") * people;
            case "airport", "station" -> level * LEVEL_UNIT_PRICE.get("airport");
            default -> level * 100;
        };
    }

    static String countUnit(String type) {
        return switch (type) {
            case "hotel" -> "晚";
            case "restaurant" -> "次";
            default -> null;
        };
    }

    /**
     * 按"每日行程"里实际安排的项目逐项估算。
     *
     * <p>市内交通单独一行，因为不管有没有收藏地点，这趟旅行都需要它。
     */
    public static Estimate estimateItineraryBudget(
            List<Map<String, Object>> itinerary,
            int tripDays,
            Integer tripBudget,
            Map<String, Object> travelerProfile) {
        int days = Math.max(1, tripDays);
        int people = Math.max(1, toInt(travelerProfile == null ? null : travelerProfile.get("total_people"), 1));
        int transport = DAILY_TRANSPORT * days;
        int total = transport;

        List<Map<String, Object>> breakdown = new ArrayList<>();
        breakdown.add(line("transport", "市内交通基础估算", transport, "baseline"));
        boolean usedDefaults = false;
        java.util.Set<String> categoriesSeen = new java.util.HashSet<>();

        for (Map<String, Object> item : itinerary) {
            String placeType = typeOf(item);
            if (placeType.isEmpty()) {
                placeType = "attraction";
            }
            categoriesSeen.add(placeType);
            Object rawName = item.get("placeName");
            if (rawName == null) {
                rawName = item.get("place_name");
            }
            if (rawName == null) {
                rawName = item.get("name");
            }
            String name = rawName == null ? "行程项" : String.valueOf(rawName);
            String unit = countUnit(placeType);
            Integer quoted = quotedCost(item);
            int quantity = unit != null ? quantityOf(item) : 1;
            int unitPrice;
            int amount;
            String source;
            if (quoted != null) {
                amount = quoted;
                unitPrice = quantity > 1 ? (int) Math.round((double) quoted / quantity) : quoted;
                source = "quoted";
            } else {
                int[] level = levelOf(item);
                usedDefaults = usedDefaults || level[1] == 1;
                unitPrice = levelUnitPrice(placeType, level[0], people);
                amount = unitPrice * quantity;
                source = "level";
            }
            total += amount;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", placeType);
            row.put("name", name);
            row.put("amount", amount);
            row.put("unit_price", unitPrice);
            row.put("quantity", quantity);
            row.put("quantity_unit", unit);
            row.put("people", "restaurant".equals(placeType) ? people : null);
            row.put("price_level", item.get("priceLevel") != null ? item.get("priceLevel") : item.get("price_level"));
            row.put("source", source);
            row.put("date", dateText(item.get("date")));
            row.put("start_time", clockText(item.get("startTime"), item.get("start_time")));
            row.put("end_time", clockText(item.get("endTime"), item.get("end_time")));
            row.put("item_id", item.get("id"));
            row.put("place_id", item.get("placeId") != null ? item.get("placeId") : item.get("place_id"));
            row.put("operation_index", item.get("operation_index"));
            row.put("note", item.get("note"));
            breakdown.add(row);
        }

        List<Map<String, Object>> excluded = new ArrayList<>();
        if (!categoriesSeen.contains("hotel")) {
            excluded.add(line(
                    "hotel", "住宿参考（未计入总额）",
                    REFERENCE_HOTEL_PER_NIGHT * Math.max(1, days - 1), null));
        }
        if (!categoriesSeen.contains("restaurant")) {
            excluded.add(line(
                    "restaurant", "餐饮参考（未计入总额）",
                    REFERENCE_MEAL_PER_PERSON_PER_DAY * days * people, null));
        }

        StringBuilder note = new StringBuilder(
                "按“每日行程”里实际安排的项目逐项估算，删掉的项目不再计费；含市内交通基础估算，实际费用以商家报价为准。");
        if (usedDefaults) {
            note.append(" 没有价格的项目已按类型使用中档默认值。");
        }
        if (!excluded.isEmpty()) {
            note.append(" 行程里还没有安排的类别，其参考金额列在下方且未计入总额。");
        }
        return new Estimate(
                total,
                tripBudget,
                tripBudget == null ? null : tripBudget - total,
                "CNY",
                note.toString(),
                breakdown,
                (int) Math.round((double) total / days),
                formatItemizedSummary(breakdown, total),
                excluded);
    }

    /** 按收藏地点的价格等级估一趟旅行。 */
    public static Estimate estimateBudget(
            List<Map<String, Object>> places,
            int tripDays,
            Integer tripBudget,
            Map<String, Object> travelerProfile) {
        int days = Math.max(1, tripDays);
        int people = toInt(travelerProfile == null ? null : travelerProfile.get("total_people"), 0);
        int total = 0;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        boolean usedDefaults = false;

        for (Map<String, Object> place : places) {
            String placeType = typeOf(place);
            Object rawName = place.get("name");
            String name = rawName == null ? "地点" : String.valueOf(rawName);
            Integer quoted = quotedCost(place);
            if (quoted != null) {
                total += quoted;
                breakdown.add(line(placeType, name, quoted, "quoted"));
                continue;
            }
            int[] level = levelOf(place);
            usedDefaults = usedDefaults || level[1] == 1;
            int amount = levelAmount(placeType, level[0], days, Math.max(1, people));
            total += amount;
            breakdown.add(line(placeType, name, amount, "level"));
        }

        if (places.isEmpty()) {
            // 还没有任何已订项目：只收市内交通。住宿与餐饮不预设，避免与用户后来勾选的真实价格重复计费。
            int transportPeople = Math.max(1, people);
            int nights = Math.max(1, days - 1);
            int transport = DAILY_TRANSPORT * days;
            total = transport;
            usedDefaults = true;
            breakdown = new ArrayList<>();
            breakdown.add(line("transport", "市内交通基础估算", transport, "baseline"));
            excluded = new ArrayList<>();
            excluded.add(line("hotel", "住宿参考（未计入总额）", REFERENCE_HOTEL_PER_NIGHT * nights, null));
            excluded.add(line(
                    "restaurant", "餐饮参考（未计入总额）",
                    REFERENCE_MEAL_PER_PERSON_PER_DAY * days * transportPeople, null));
        }

        StringBuilder note = new StringBuilder("根据地点价格等级估算，实际费用以商家报价为准。");
        if (usedDefaults) {
            note.append(" 未填写价格等级的地点已按类型使用中档默认值。");
        }
        if (!excluded.isEmpty()) {
            note.append(" 住宿与餐饮不预设基础估算，只按你已勾选或已收藏的具体项目计入总额；下方参考金额未计入。");
        }
        return new Estimate(
                total,
                tripBudget,
                tripBudget == null ? null : tripBudget - total,
                "CNY",
                note.toString(),
                breakdown,
                (int) Math.round((double) total / days),
                formatItemizedSummary(breakdown, total),
                excluded);
    }

    /** 按类汇总成一句「住宿约 X；餐饮约 Y」。 */
    public static String budgetSummary(Estimate estimate) {
        Map<String, String> labels = Map.of(
                "hotel", "住宿", "restaurant", "餐饮", "attraction", "景点",
                "airport", "交通", "station", "交通", "transport", "交通");
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Map<String, Object> line : estimate.breakdown()) {
            String category = labels.getOrDefault(String.valueOf(line.get("category")), "其他");
            totals.merge(category, toInt(line.get("amount"), 0), Integer::sum);
        }
        if (totals.isEmpty()) {
            return "基础支出约 " + estimate.totalEstimate() + " " + estimate.currency();
        }
        List<String> parts = new ArrayList<>();
        totals.forEach((category, amount) ->
                parts.add(category + "约 " + amount + " " + estimate.currency()));
        return String.join("；", parts);
    }

    private static Map<String, Object> line(String category, String name, int amount, String source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("category", category);
        row.put("name", name);
        row.put("amount", amount);
        row.put("source", source);
        return row;
    }

    private static String dateText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() > 10 ? text.substring(0, 10) : text;
    }

    private static String clockText(Object primary, Object fallback) {
        Object value = primary != null ? primary : fallback;
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).replace("T", " ");
        int space = text.lastIndexOf(' ');
        if (space >= 0) {
            text = text.substring(space + 1);
        }
        return text.length() >= 5 ? text.substring(0, 5) : text;
    }

    static int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(value).replace(",", "")));
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    static String lower(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }
}
