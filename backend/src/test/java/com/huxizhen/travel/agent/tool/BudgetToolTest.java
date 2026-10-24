package com.huxizhen.travel.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 预算数字的回归测试。
 *
 * <p>这些数字前端会直接显示，且原项目的 pytest 用例逐条断言过；公式一旦漂移，
 * 用户看到的总额就和"收藏地点的报价"对不上。
 */
class BudgetToolTest {

    private static Map<String, Object> place(String type, Integer priceLevel, Integer quoted) {
        Map<String, Object> place = new LinkedHashMap<>();
        place.put("name", type + "-1");
        place.put("type", type);
        place.put("price_level", priceLevel);
        if (quoted != null) {
            place.put("quoted_cost", quoted);
        }
        return place;
    }

    private static Map<String, Object> profile(int people) {
        return Map.of("total_people", people);
    }

    @Test
    void hotelIsChargedPerNightAndRestaurantPerDayPerPerson() {
        // 260/晚 × 3 晚 + 80×2档 × 4天 × 2人
        BudgetTool.Estimate estimate = BudgetTool.estimateBudget(
                List.of(place("hotel", 1, null), place("restaurant", 2, null)),
                4,
                null,
                profile(2));

        assertThat(estimate.totalEstimate()).isEqualTo(260 * 3 + 80 * 2 * 4 * 2);
        assertThat(estimate.dailyEstimate()).isEqualTo(Math.round(estimate.totalEstimate() / 4.0f));
    }

    @Test
    void quotedPriceBeatsPriceLevel() {
        BudgetTool.Estimate estimate = BudgetTool.estimateBudget(
                List.of(place("hotel", 5, 1200)), 3, null, profile(2));

        assertThat(estimate.totalEstimate()).isEqualTo(1200);
        assertThat(estimate.breakdown().get(0).get("source")).isEqualTo("quoted");
    }

    @Test
    void emptyCollectionChargesOnlyLocalTransportAndExcludesRoomAndMeals() {
        BudgetTool.Estimate estimate = BudgetTool.estimateBudget(List.of(), 4, 8000, profile(2));

        assertThat(estimate.totalEstimate()).isEqualTo(60 * 4);
        assertThat(estimate.remainingBudget()).isEqualTo(8000 - 240);
        // 住宿与餐饮只作为参考金额出现，不进总额
        assertThat(estimate.excludedAllowances()).hasSize(2);
        assertThat(estimate.excludedAllowances().get(0).get("category")).isEqualTo("hotel");
        assertThat(estimate.note()).contains("下方参考金额未计入");
    }

    @Test
    void attractionFallsBackToLevelTimesHundred() {
        BudgetTool.Estimate estimate = BudgetTool.estimateBudget(
                List.of(place("attraction", 3, null)), 1, null, profile(1));

        assertThat(estimate.totalEstimate()).isEqualTo(300);
        // 有收藏地点时不再额外给"参考金额"（只有空收藏时才给，与原实现一致）
        assertThat(estimate.excludedAllowances()).isEmpty();
    }

    @Test
    void itineraryBudgetChargesOnlyScheduledItemsPlusTransport() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("place_name", "夫子庙");
        item.put("type", "attraction");
        item.put("price_level", 2);
        item.put("date", "2026-11-01");
        item.put("start_time", "09:30:00");

        BudgetTool.Estimate estimate = BudgetTool.estimateItineraryBudget(
                List.of(item), 2, 1000, profile(2));

        // 市内交通 60×2 + 门票 level2×100（门票按人头的乘法在别处，公式这里是单价）
        assertThat(estimate.totalEstimate()).isEqualTo(120 + 200);
        assertThat(estimate.breakdown().get(0).get("category")).isEqualTo("transport");
        assertThat(estimate.breakdown().get(1).get("unit_price")).isEqualTo(200);
    }

    @Test
    void hotelInItineraryUsesQuantityForNights() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("place_name", "金陵饭店");
        item.put("type", "hotel");
        item.put("price_level", 2);
        item.put("quoted_cost", 900);
        item.put("quantity", 3);

        BudgetTool.Estimate estimate = BudgetTool.estimateItineraryBudget(
                List.of(item), 4, null, profile(2));

        // 报价 900 已覆盖 3 晚，直接计 900；单价展示为 900/3
        assertThat(estimate.totalEstimate()).isEqualTo(60 * 4 + 900);
        assertThat(estimate.breakdown().get(1).get("unit_price")).isEqualTo(300);
        assertThat(estimate.breakdown().get(1).get("quantity_unit")).isEqualTo("晚");
    }

    @Test
    void itemizedSummaryIsAnAdditionExpression() {
        String summary = BudgetTool.formatItemizedSummary(
                List.of(Map.of("name", "住宿", "amount", 780), Map.of("name", "门票", "amount", 0)),
                780);

        assertThat(summary).isEqualTo("住宿（780元） + 门票（免费） = 780元");
    }

    @Test
    void budgetSummaryGroupsCategories() {
        BudgetTool.Estimate estimate = BudgetTool.estimateBudget(
                List.of(place("hotel", 1, null), place("attraction", 1, null)), 2, null, profile(1));

        assertThat(BudgetTool.budgetSummary(estimate)).contains("住宿约 260 CNY").contains("景点约 100 CNY");
    }
}
