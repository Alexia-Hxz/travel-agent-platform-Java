package com.huxizhen.travel.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.huxizhen.travel.dto.plan.PlanDtos;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 分类预算框架：最大余数法必须让各行相加**恰好**等于计划金额。 */
class BudgetPlanToolTest {

    @Test
    void plannedTotalIsEightyPercentOfBudget() {
        assertThat(BudgetPlanTool.plannedTotal(8000)).isEqualTo(6400);
        assertThat(BudgetPlanTool.plannedTotal(0)).isNull();
        assertThat(BudgetPlanTool.plannedTotal(null)).isNull();
    }

    @Test
    void rowsAlwaysAddUpToPlannedTotal() {
        for (int budget : new int[] {1000, 3333, 8000, 12345}) {
            PlanDtos.BudgetPlanRead plan = BudgetPlanTool.build(
                    budget, 4, Map.of("total_people", 2), null);
            int sum = plan.categories().stream().mapToInt(PlanDtos.BudgetCategoryRead::amount).sum();
            assertThat(sum).as("budget %s", budget).isEqualTo(plan.plannedTotal());
        }
    }

    @Test
    void defaultSharesAreUsedWhenNoModelAnswer() {
        PlanDtos.BudgetPlanRead plan = BudgetPlanTool.build(10000, 5, Map.of("total_people", 2), null);

        assertThat(plan.status()).isEqualTo("estimated");
        assertThat(plan.source()).isEqualTo("default");
        assertThat(plan.plannedTotal()).isEqualTo(8000);
        assertThat(plan.reserveAmount()).isEqualTo(2000);
        assertThat(plan.categories()).hasSize(5);
        // 8000 × 45% = 3600
        assertThat(plan.categories().get(0).amount()).isEqualTo(3600);
        assertThat(plan.categories().get(0).label()).isEqualTo("酒店 / 住宿");
    }

    @Test
    void dayTripPutsNoMoneyIntoHotel() {
        // 1 天行程：住宿份额为 0，钱进餐饮/门票/交通/其他
        Map<String, Double> shares = Map.of(
                "hotel", 0.0, "restaurant", 0.40, "attraction", 0.25,
                "transport", 0.20, "other", 0.15);
        PlanDtos.BudgetPlanRead plan = BudgetPlanTool.build(1000, 1, Map.of("total_people", 1), shares);

        assertThat(plan.nights()).isZero();
        assertThat(plan.categories().get(0).amount()).isZero();
        // 金额为 0 的分类不写单位说明（与原实现一致）
        assertThat(plan.categories().get(0).unit()).isEmpty();
        assertThat(plan.source()).isEqualTo("model");
    }

    @Test
    void insaneModelAnswerFallsBackToDefaults() {
        // 购物占到 31%：明显不合理，必须丢弃整份答案而不是"归一化"它
        Map<String, Double> shares = Map.of(
                "hotel", 0.20, "restaurant", 0.20, "attraction", 0.20,
                "transport", 0.09, "other", 0.31);
        PlanDtos.BudgetPlanRead plan = BudgetPlanTool.build(10000, 5, Map.of("total_people", 2), shares);

        assertThat(plan.source()).isEqualTo("default");
    }

    @Test
    void missingBudgetYieldsUnavailableWithExplanation() {
        PlanDtos.BudgetPlanRead plan = BudgetPlanTool.build(null, 4, Map.of("total_people", 2), null);

        assertThat(plan.status()).isEqualTo("unavailable");
        assertThat(plan.categories()).isEmpty();
        assertThat(plan.note()).contains("还没有填写总预算");
    }

    @Test
    void summaryMentionsEveryNonZeroCategory() {
        PlanDtos.BudgetPlanRead plan = BudgetPlanTool.build(10000, 4, Map.of("total_people", 2), null);
        String summary = BudgetPlanTool.formatSummary(plan);

        assertThat(summary).contains("酒店 / 住宿约").contains("合计约 8000 CNY（按总预算估算）");
    }

    @Test
    void unitNotesDescribeThePerNightAndPerPersonCost() {
        PlanDtos.BudgetPlanRead plan = BudgetPlanTool.build(8000, 4, Map.of("total_people", 2), null);

        assertThat(plan.categories().get(0).unit()).contains("CNY/晚 × 3 晚");
        assertThat(plan.categories().get(1).unit()).contains("CNY/人/天 × 2 人 × 4 天");
    }

    @Test
    void splitUsesLargestRemainder() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("a", 0.5);
        weights.put("b", 0.3);
        weights.put("c", 0.2);

        Map<String, Integer> amounts = BudgetPlanTool.split(101, weights);

        assertThat(amounts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(101);
        assertThat(amounts.get("a")).isEqualTo(51);
    }
}
