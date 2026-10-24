package com.huxizhen.travel.controller;

import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.dto.plan.PlanDtos;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.service.PlanningJobService;
import com.huxizhen.travel.service.PlanningService;
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
 * 规划流程的 HTTP 入口。
 *
 * <p>{@code POST /plan} 是同步版（老客户端仍可用），{@code /plan-jobs} 是异步版：
 * 立即返回 {@code 202} 与一个 job id，前端每 800ms 轮询一次进度。
 */
@RestController
@RequestMapping("/api/v1/trips")
public class AgentController {

    private final TripService tripService;
    private final PlanningService planningService;
    private final PlanningJobService planningJobService;

    public AgentController(
            TripService tripService,
            PlanningService planningService,
            PlanningJobService planningJobService) {
        this.tripService = tripService;
        this.planningService = planningService;
        this.planningJobService = planningJobService;
    }

    @PostMapping("/{tripId}/plan")
    public PlanDtos.TravelPlanRead createPlan(
            @PathVariable Long tripId, @Valid @RequestBody PlanDtos.TravelPlanRequest payload) {
        Trip trip = requireTrip(tripId);
        return planningService.createPlan(
                trip,
                payload.query(),
                payload.includeWeb(),
                payload.selectedRecommendations() == null ? List.of() : payload.selectedRecommendations(),
                payload.adjustmentSummary());
    }

    @PostMapping("/{tripId}/plan-jobs")
    public ResponseEntity<PlanDtos.PlanningJobRead> createJob(
            @PathVariable Long tripId, @Valid @RequestBody PlanDtos.TravelPlanRequest payload) {
        Trip trip = requireTrip(tripId);
        PlanDtos.PlanningJobRead job = planningJobService.start(
                trip,
                payload.query(),
                payload.includeWeb(),
                payload.selectedRecommendations() == null ? List.of() : payload.selectedRecommendations(),
                payload.adjustmentSummary());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/{tripId}/plan-jobs/{jobId}")
    public PlanDtos.PlanningJobRead readJob(@PathVariable Long tripId, @PathVariable String jobId) {
        // 先解析 trip，让归属/404 语义保持与其它端点一致。
        requireTrip(tripId);
        PlanDtos.PlanningJobRead job = planningJobService.get(jobId, tripId);
        if (job == null) {
            throw ApiException.notFound("规划任务不存在。");
        }
        return job;
    }

    private Trip requireTrip(Long tripId) {
        Trip trip = tripService.get(tripId);
        if (trip == null) {
            throw ApiException.notFound("旅行项目不存在。");
        }
        return trip;
    }

    /** 请求体校验用的最小约束（与 Pydantic 的 1..500 一致）。 */
    public record QueryOnly(@Size(min = 1, max = 500) String query) {
    }
}
