package com.huxizhen.travel.dto.plan;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 规划流水线的全部出入参。
 *
 * <p>字段名与顺序逐条对齐 Python 的 {@code app/schemas/agent.py}；Jackson 的 SNAKE_CASE
 * 负责把 {@code tripId} 写成 {@code trip_id}。
 *
 * <p>{@code place_recommendations} 是**白名单**：Python 里 `_compose` 把 30 个字段逐一列出，
 * 少一个就永远到不了前端。这里同样只声明这 30 个。
 */
public final class PlanDtos {

    private PlanDtos() {
    }

    public record SelectedRecommendation(
            @Size(min = 1, max = 160) String name,
            @Size(max = 40) String category,
            Integer estimatedCost,
            @Size(max = 600) String reason) {
    }

    public record TravelPlanRequest(
            @NotNull @Size(min = 1, max = 500) String query,
            boolean includeWeb,
            List<SelectedRecommendation> selectedRecommendations,
            @Size(max = 4000) String adjustmentSummary) {
    }

    public record PlanItem(
            String placeName,
            String startTime,
            String endTime,
            String note,
            String imageUrl,
            String fallbackImageUrl) {
    }

    public record DailyPlan(LocalDate date, List<PlanItem> items, String shortage) {
    }

    public record BudgetRead(
            int totalEstimate,
            Integer tripBudget,
            Integer remainingBudget,
            String currency,
            String note,
            List<Map<String, Object>> breakdown,
            int dailyEstimate,
            String budgetSummary,
            String itemizedSummary,
            List<Map<String, Object>> excludedAllowances) {
    }

    public record ConflictRead(
            LocalDate date, String firstPlace, String secondPlace, String message) {
    }

    public record PlanSource(
            String sourceType,
            String title,
            String url,
            String snippet,
            Double score,
            String imageUrl) {
    }

    public record PlaceRecommendation(
            String name,
            String category,
            String reason,
            String priceHint,
            Integer unitPrice,
            String priceUnit,
            Integer estimatedCost,
            String costExplanation,
            int quantity,
            String quantityUnit,
            Integer quantityMax,
            String costSource,
            String imageUrl,
            String fallbackImageUrl,
            String imageCredit,
            String imageCreditUrl,
            Double latitude,
            Double longitude,
            Double rating,
            String signatureDish,
            String mealSlot,
            boolean overBudget,
            Integer budgetLimit,
            String budgetNote,
            String priceTier,
            String priceTierLabel,
            boolean priceAdjusted,
            boolean isFree,
            boolean pricePending) {
    }

    public record ToolEvent(String tool, String status, String message) {
    }

    public record TransportAdvice(String origin, String destination, String recommendation) {
    }

    public record PlanHandoff(
            List<SelectedRecommendation> selectedRecommendations,
            int selectedTotal,
            boolean hasAdjustmentSummary,
            int savedPlaceCount,
            List<String> savedPlaceNames) {
    }

    public record WeatherAdvice(
            String date,
            String condition,
            Integer precipitationProbability,
            boolean indoorPriority,
            List<String> scheduled,
            List<String> outdoorItems,
            List<String> indoorItems,
            String message,
            String suggestion) {
    }

    public record BudgetCategoryRead(
            String category, String label, int amount, double share, int sharePercent, String unit) {
    }

    public record BudgetPlanRead(
            String status,
            String currency,
            Integer tripBudget,
            int plannedTotal,
            double plannedRatio,
            int plannedRatioPercent,
            int reserveAmount,
            String source,
            int days,
            int nights,
            int people,
            List<BudgetCategoryRead> categories,
            String note,
            String summary) {
    }

    public record TravelPlanRead(
            Long tripId,
            String summary,
            String finalSummary,
            List<PlanSource> recommendations,
            List<PlaceRecommendation> placeRecommendations,
            TransportAdvice transportAdvice,
            List<DailyPlan> dailyPlan,
            BudgetRead budget,
            BudgetPlanRead budgetPlan,
            List<ConflictRead> conflicts,
            List<PlanSource> sources,
            List<ToolEvent> events,
            Map<String, Object> travelerProfile,
            Map<String, Object> weather,
            List<WeatherAdvice> weatherAdvice,
            String weatherAdjustmentRequest,
            String budgetSummary,
            PlanHandoff handoff) {
    }

    public record PlanningJobRead(
            String jobId,
            Long tripId,
            String status,
            String phase,
            int progress,
            List<ToolEvent> events,
            List<PlaceRecommendation> placeRecommendations,
            TravelPlanRead result,
            String error) {
    }
}
