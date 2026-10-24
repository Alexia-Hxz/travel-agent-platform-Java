package com.huxizhen.travel.agent.tool;

import com.huxizhen.travel.dto.plan.PlanDtos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「你的预算大概该花在哪」的分类框架。
 *
 * <p>这是**估算**，不是求和：它等于 {@code 总预算 × 80%} 再按类别拆分。
 * 80% 是刻意留白——真实旅行一定有购物、雨天打车、临时取消，声称把最后一分钱都安排好
 * 是没人能兑现的承诺。拆分用最大余数法，保证各行相加**恰好**等于计划金额。
 *
 * <p>模型给的份额只在合理区间内才被采用（例如"其他"不能超过 15%），否则回落到默认份额。
 */
public final class BudgetPlanTool {

    public static final double PLANNED_BUDGET_RATIO = 0.8;

    public static final List<String[]> DEFAULT_SHARES = List.of(
            new String[] {"hotel", "酒店 / 住宿", "0.45"},
            new String[] {"restaurant", "美食 / 餐饮", "0.25"},
            new String[] {"attraction", "景点门票", "0.15"},
            new String[] {"transport", "市内交通", "0.10"},
            new String[] {"other", "其他 / 购物机动", "0.05"});

    public static final List<String[]> DAY_TRIP_SHARES = List.of(
            new String[] {"hotel", "酒店 / 住宿", "0.0"},
            new String[] {"restaurant", "美食 / 餐饮", "0.40"},
            new String[] {"attraction", "景点门票", "0.25"},
            new String[] {"transport", "市内交通", "0.20"},
            new String[] {"other", "其他 / 购物机动", "0.15"});

    private static final Map<String, double[]> RANGES = Map.of(
            "hotel", new double[] {0.05, 0.60},
            "restaurant", new double[] {0.10, 0.40},
            "attraction", new double[] {0.02, 0.35},
            "transport", new double[] {0.01, 0.25},
            "other", new double[] {0.0, 0.15});

    public static final String SYSTEM_PROMPT =
            "你是旅行预算规划师。用户会给出目的地、天数、人数、总预算和一个待分配金额，"
                    + "请把待分配金额按类别拆开。只输出 JSON 对象，不要任何解释、不要 Markdown 代码块。"
                    + "每个类别都必须给出正整数金额，所有类别之和必须恰好等于待分配金额。"
                    + "请结合该目的地的真实行情分配（例如日本住宿贵、市内交通便宜；东南亚住宿便宜）。"
                    + "经验区间：住宿 30%-55%、餐饮 15%-35%、景点门票 5%-30%、市内交通 3%-20%、"
                    + "其他（购物机动）不要超过 15%，当日往返可以不给住宿。";

    public static final String UNALLOCATED_NOTE =
            "这个行程还没有填写总预算，所以暂时无法按预算给出分配建议。"
                    + "在项目里填上总预算后，这里会按你的预算给出酒店/美食/景点等各类别的大概金额。";

    private BudgetPlanTool() {
    }

    public static Integer positiveInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            int number = (int) Math.round(Double.parseDouble(String.valueOf(value)));
            return number > 0 ? number : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    public static Integer plannedTotal(Object tripBudget) {
        Integer budget = positiveInt(tripBudget);
        if (budget == null) {
            return null;
        }
        int planned = (int) Math.round(budget * PLANNED_BUDGET_RATIO);
        return planned > 0 ? planned : null;
    }

    /**
     * 生成分类框架。{@code modelShares} 为 null 时用默认份额；给了但不在合理区间也回落。
     */
    public static PlanDtos.BudgetPlanRead build(
            Integer tripBudget,
            int tripDays,
            Map<String, Object> travelerProfile,
            Map<String, Double> modelShares) {
        int days = Math.max(1, tripDays);
        int nights = Math.max(0, days - 1);
        int people = people(travelerProfile);
        Integer budget = positiveInt(tripBudget);
        Integer total = plannedTotal(tripBudget);

        if (budget == null || total == null) {
            return new PlanDtos.BudgetPlanRead(
                    "unavailable", "CNY", null, 0, PLANNED_BUDGET_RATIO,
                    (int) Math.round(PLANNED_BUDGET_RATIO * 100), 0, "none",
                    days, nights, people, List.of(), UNALLOCATED_NOTE, "");
        }

        boolean sane = isSane(modelShares, nights);
        Map<String, Double> weights = weights(sane ? modelShares : null, nights);
        Map<String, Integer> amounts = split(total, weights);

        List<PlanDtos.BudgetCategoryRead> categories = new ArrayList<>();
        for (String[] row : DEFAULT_SHARES) {
            String key = row[0];
            String label = row[1];
            int amount = amounts.getOrDefault(key, 0);
            categories.add(new PlanDtos.BudgetCategoryRead(
                    key,
                    label,
                    amount,
                    total == 0 ? 0.0 : round4((double) amount / total),
                    total == 0 ? 0 : (int) Math.round((double) amount / total * 100),
                    unitNote(key, amount, days, nights, people)));
        }

        int reserve = Math.max(0, budget - total);
        String note = "按你填写的总预算 " + budget + " CNY 估算：计划花掉约 " + total + " CNY（"
                + Math.round(PLANNED_BUDGET_RATIO * 100) + "%），预留约 " + reserve + " CNY（"
                + remainingRatio() + "%）给购物与临时支出。"
                + "这是还没有挑选具体项目时的花费框架，只用来判断钱大概花在哪一类；"
                + "挑好项目之后的真实估算在“编辑每日行程”面板里。";
        String summary = formatSummary(categories, total, "CNY");
        return new PlanDtos.BudgetPlanRead(
                "estimated", "CNY", budget, total, PLANNED_BUDGET_RATIO,
                (int) Math.round(PLANNED_BUDGET_RATIO * 100), reserve,
                sane ? "model" : "default", days, nights, people, categories, note, summary);
    }

    /** 模型份额是否值得展示：必须覆盖全部分类，且每一类都落在经验区间内。 */
    static boolean isSane(Map<String, Double> shares, int nights) {
        if (shares == null || shares.isEmpty()) {
            return false;
        }
        for (String[] row : DEFAULT_SHARES) {
            if (!shares.containsKey(row[0])) {
                return false;
            }
        }
        double sum = shares.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            return false;
        }
        for (Map.Entry<String, double[]> entry : RANGES.entrySet()) {
            double low = entry.getValue()[0];
            double high = entry.getValue()[1];
            if ("hotel".equals(entry.getKey()) && nights <= 0) {
                low = 0.0;
            }
            double share = shares.getOrDefault(entry.getKey(), 0.0) / sum;
            if (share < low - 1e-9 || share > high + 1e-9) {
                return false;
            }
        }
        return true;
    }

    static Map<String, Double> weights(Map<String, Double> shares, int nights) {
        List<String[]> defaults = nights > 0 ? DEFAULT_SHARES : DAY_TRIP_SHARES;
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String[] row : defaults) {
            weights.put(row[0], Double.parseDouble(row[2]));
        }
        if (shares != null) {
            for (Map.Entry<String, Double> entry : shares.entrySet()) {
                // 模型给的 0 是真实答案（当日往返没有住宿），不能被默认份额覆盖。
                if (weights.containsKey(entry.getKey()) && entry.getValue() != null && entry.getValue() >= 0) {
                    weights.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return weights;
    }

    /** 最大余数法：先截断，再把差出来的几块钱按小数部分从大到小补齐。 */
    static Map<String, Integer> split(int total, Map<String, Double> weights) {
        double sum = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum == 0) {
            sum = 1.0;
        }
        Map<String, Double> exact = new LinkedHashMap<>();
        Map<String, Integer> amounts = new LinkedHashMap<>();
        int assigned = 0;
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            double value = total * entry.getValue() / sum;
            exact.put(entry.getKey(), value);
            int truncated = (int) value;
            amounts.put(entry.getKey(), truncated);
            assigned += truncated;
        }
        int leftover = total - assigned;
        if (leftover > 0) {
            List<String> order = new ArrayList<>(exact.keySet());
            order.sort((left, right) -> Double.compare(
                    exact.get(right) - amounts.get(right), exact.get(left) - amounts.get(left)));
            for (int index = 0; index < leftover; index++) {
                String key = order.get(index % order.size());
                amounts.put(key, amounts.get(key) + 1);
            }
        }
        return amounts;
    }

    static String unitNote(String category, int amount, int days, int nights, int people) {
        if (amount <= 0) {
            return "";
        }
        return switch (category) {
            case "hotel" -> nights <= 0
                    ? "当日往返，可不安排住宿"
                    : "约 " + Math.round((double) amount / nights) + " CNY/晚 × " + nights + " 晚";
            case "restaurant" -> "约 " + Math.round((double) amount / (days * people)) + " CNY/人/天 × "
                    + people + " 人 × " + days + " 天";
            case "attraction" -> "约 " + Math.round((double) amount / people) + " CNY/人 × " + people
                    + " 人（全程门票）";
            case "transport" -> "约 " + Math.round((double) amount / days) + " CNY/天 × " + days + " 天";
            default -> "含购物、临时打车与机动支出";
        };
    }

    public static int remainingRatio() {
        return (int) Math.round((1 - PLANNED_BUDGET_RATIO) * 100);
    }

    public static String formatSummary(
            List<PlanDtos.BudgetCategoryRead> categories, int plannedTotal, String currency) {
        List<String> parts = new ArrayList<>();
        for (PlanDtos.BudgetCategoryRead row : categories) {
            if (row.amount() != 0) {
                parts.add(row.label() + "约 " + row.amount() + " " + currency);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return String.join("，", parts) + "；合计约 " + plannedTotal + " " + currency + "（按总预算估算）";
    }

    public static String formatSummary(PlanDtos.BudgetPlanRead plan) {
        if (plan == null || !"estimated".equals(plan.status())) {
            return "";
        }
        return formatSummary(plan.categories(), plan.plannedTotal(), plan.currency());
    }

    private static int people(Map<String, Object> travelerProfile) {
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

    static double round4(double value) {
        return java.math.BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }
}
