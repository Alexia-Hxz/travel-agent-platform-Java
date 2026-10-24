package com.huxizhen.travel.controller;

import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.dto.adjustment.AdjustmentDtos;
import com.huxizhen.travel.entity.ItineraryAdjustment;
import com.huxizhen.travel.entity.ItineraryItem;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.service.AdjustmentService;
import com.huxizhen.travel.service.TripService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 行程调整：草案 → 逐条应用 / 拒绝。
 *
 * <p>这是产品"两阶段写入"的 HTTP 面：{@code POST /adjustments} 只生成草案，
 * {@code POST .../apply} 才写行程。返回的 {@code itinerary_item_ids} 是**应用后整趟行程**
 * 的项目 id（与原实现一致），不是只含新增项。
 */
@RestController
@RequestMapping("/api/v1/trips")
public class AdjustmentController {

    private final TripService tripService;
    private final AdjustmentService adjustmentService;

    public AdjustmentController(TripService tripService, AdjustmentService adjustmentService) {
        this.tripService = tripService;
        this.adjustmentService = adjustmentService;
    }

    @PostMapping("/{tripId}/adjustments")
    public ResponseEntity<AdjustmentDtos.AdjustmentRead> create(
            @PathVariable Long tripId, @Valid @RequestBody AdjustmentDtos.AdjustmentDraftRequest payload) {
        Trip trip = requireTrip(tripId);
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(adjustmentService.createDraft(trip, payload));
        } catch (AdjustmentService.AdjustmentValidationException
                | AdjustmentService.AdjustmentStateException error) {
            throw ApiException.unprocessable(error.getMessage());
        }
    }

    @GetMapping("/{tripId}/adjustments")
    public List<AdjustmentDtos.AdjustmentRead> list(@PathVariable Long tripId) {
        requireTrip(tripId);
        return adjustmentService.list(tripId).stream().map(adjustmentService::toRead).toList();
    }

    @PostMapping("/{tripId}/adjustments/{adjustmentId}/apply")
    public AdjustmentDtos.AdjustmentApplyRead apply(
            @PathVariable Long tripId,
            @PathVariable Long adjustmentId,
            @RequestBody(required = false) AdjustmentDtos.AdjustmentApplyRequest payload) {
        Trip trip = requireTrip(tripId);
        ItineraryAdjustment adjustment = requireAdjustment(tripId, adjustmentId);
        try {
            List<ItineraryItem> items = adjustmentService.apply(
                    trip,
                    adjustment,
                    payload == null ? null : payload.operationIndexes(),
                    payload == null ? null : payload.operations());
            return new AdjustmentDtos.AdjustmentApplyRead(
                    adjustment.getId(), "applied", items.stream().map(ItineraryItem::getId).toList());
        } catch (AdjustmentService.AdjustmentStateException error) {
            throw ApiException.conflict(error.getMessage());
        } catch (AdjustmentService.AdjustmentValidationException error) {
            throw ApiException.unprocessable(error.getMessage());
        }
    }

    @PostMapping("/{tripId}/adjustments/{adjustmentId}/reject")
    public AdjustmentDtos.AdjustmentRead reject(
            @PathVariable Long tripId, @PathVariable Long adjustmentId) {
        requireTrip(tripId);
        ItineraryAdjustment adjustment = requireAdjustment(tripId, adjustmentId);
        try {
            return adjustmentService.toRead(adjustmentService.reject(adjustment));
        } catch (AdjustmentService.AdjustmentStateException error) {
            throw ApiException.conflict(error.getMessage());
        }
    }

    private Trip requireTrip(Long tripId) {
        Trip trip = tripService.get(tripId);
        if (trip == null) {
            throw ApiException.notFound("旅行项目不存在。");
        }
        return trip;
    }

    private ItineraryAdjustment requireAdjustment(Long tripId, Long adjustmentId) {
        ItineraryAdjustment adjustment = adjustmentService.get(tripId, adjustmentId);
        if (adjustment == null) {
            throw ApiException.notFound("行程调整草案不存在。");
        }
        return adjustment;
    }

    /** 请求体里 instruction 的长度约束（与 Pydantic 的 1..500 一致）。 */
    public record InstructionOnly(@Size(min = 1, max = 500) String instruction) {
    }
}
