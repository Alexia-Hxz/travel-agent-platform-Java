package com.huxizhen.travel.dto.adjustment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 行程调整的全部出入参，字段与 Python 的 {@code app/schemas/adjustment.py} 一致。 */
public final class AdjustmentDtos {

    private AdjustmentDtos() {
    }

    /** 规划面板递过来的候选地点。 */
    public record AdjustmentCandidate(
            @Size(min = 1, max = 160) String name,
            @Size(max = 20) String category,
            @Size(max = 600) String reason,
            Integer estimatedCost,
            Integer unitPrice,
            Integer quantity,
            @Size(max = 10) String quantityUnit,
            Integer priceLevel,
            @Size(max = 600) String imageUrl,
            @Size(max = 120) String signatureDish,
            Double rating) {
    }

    public record AdjustmentDraftRequest(
            @NotBlank @Size(min = 1, max = 500) String instruction,
            boolean includeWeb,
            @Size(max = 48) List<Map<String, Object>> operations,
            boolean structuredOnly,
            @Size(max = 60) List<AdjustmentCandidate> candidates,
            String mode,
            LocalDate date) {
    }

    public record AdjustmentApplyRequest(
            List<Integer> operationIndexes, List<Map<String, Object>> operations) {
    }

    public record AdjustmentRead(
            Long id,
            Long tripId,
            String instruction,
            String status,
            List<Map<String, Object>> operations,
            String summary,
            List<Map<String, Object>> sources,
            List<Map<String, Object>> events,
            Map<String, Object> budget,
            List<Map<String, Object>> conflicts,
            List<Map<String, Object>> dailyPlan,
            String finalSummary,
            Map<String, Object> travelerProfile,
            Map<String, Object> weather) {
    }

    public record AdjustmentApplyRead(Long id, String status, List<Long> itineraryItemIds) {
    }
}
