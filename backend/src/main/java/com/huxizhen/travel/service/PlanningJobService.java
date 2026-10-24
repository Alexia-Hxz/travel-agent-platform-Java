package com.huxizhen.travel.service;

import com.huxizhen.travel.agent.planner.PlanningPipeline;
import com.huxizhen.travel.dto.plan.PlanDtos;
import com.huxizhen.travel.entity.Trip;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 后台规划任务 + 可轮询的阶段快照。
 *
 * <p>规划器本身仍然是同步的：把它放进有界线程池，既保留那条被反复验证过的代码路径，
 * 又让浏览器可以轮询已完成的阶段与分类批次，而不是把一个 HTTP 请求挂上几分钟。
 *
 * <p>任务只存在内存里（原实现也是），1 小时后清理。
 */
@Service
public class PlanningJobService {

    private static final Logger log = LoggerFactory.getLogger(PlanningJobService.class);
    private static final long JOB_TTL_MILLIS = 60L * 60 * 1000;

    private final PlanningService planningService;
    private final PlanningPipeline pipeline;
    private final ExecutorService executor;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public PlanningJobService(
            PlanningService planningService,
            PlanningPipeline pipeline,
            @Value("${app.planning-job.workers:2}") int workers) {
        this.planningService = planningService;
        this.pipeline = pipeline;
        this.executor = Executors.newFixedThreadPool(Math.max(1, workers), runnable -> {
            Thread thread = new Thread(runnable, "planning-job");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 一个任务的完整状态。 */
    static final class Job {
        final String jobId;
        final long tripId;
        volatile String status = "queued";
        volatile String phase = "queued";
        volatile int progress = 0;
        volatile List<PlanDtos.ToolEvent> events = List.of();
        volatile Map<String, List<Map<String, Object>>> categoryRecommendations = new LinkedHashMap<>();
        volatile PlanDtos.TravelPlanRead result;
        volatile String error;
        volatile long updatedAt = System.currentTimeMillis();

        Job(String jobId, long tripId) {
            this.jobId = jobId;
            this.tripId = tripId;
        }
    }

    public PlanDtos.PlanningJobRead start(
            Trip trip,
            String query,
            boolean includeWeb,
            List<PlanDtos.SelectedRecommendation> selected,
            String adjustmentSummary) {
        String jobId = UUID.randomUUID().toString().replace("-", "");
        PlanningPipeline.State state = planningService.buildState(
                trip, query, includeWeb, selected, adjustmentSummary);
        Job job = new Job(jobId, trip.getId());
        jobs.put(jobId, job);
        cleanup();
        executor.submit(() -> run(job, state));
        return snapshot(job);
    }

    private void run(Job job, PlanningPipeline.State state) {
        job.status = "running";
        job.phase = "context";
        job.progress = 2;
        job.updatedAt = System.currentTimeMillis();
        try {
            PlanDtos.TravelPlanRead plan = pipeline.run(state, new PlanningPipeline.ProgressListener() {
                @Override
                public void onCategory(Map<String, Object> update) {
                    String category = String.valueOf(update.get("category"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> recommendations =
                            (List<Map<String, Object>>) update.get("recommendations");
                    job.categoryRecommendations.put(category, recommendations);
                    job.phase = "category:" + category;
                    job.progress = Math.max(job.progress, 22 + job.categoryRecommendations.size() * 12);
                    job.updatedAt = System.currentTimeMillis();
                }

                @Override
                public void onEvent(PlanDtos.ToolEvent event, String phase, int progress) {
                    job.events = List.copyOf(state.events);
                    if (phase != null) {
                        job.phase = phase;
                    }
                    job.progress = Math.max(job.progress, progress);
                    job.updatedAt = System.currentTimeMillis();
                }
            });
            job.phase = "photos";
            job.progress = 98;
            job.updatedAt = System.currentTimeMillis();

            PlanDtos.TravelPlanRead cached =
                    planningService.cachePlanPhotos(plan, state.includeWeb, state.trip.getDestination());
            job.status = "completed";
            job.phase = "completed";
            job.progress = 100;
            job.result = cached;
            job.events = cached.events();
            job.updatedAt = System.currentTimeMillis();
        } catch (RuntimeException error) {
            log.error("planning job {} failed", job.jobId, error);
            job.status = "failed";
            job.phase = "failed";
            job.error = "规划生成失败，请稍后重试。";
            job.updatedAt = System.currentTimeMillis();
        }
    }

    public PlanDtos.PlanningJobRead get(String jobId, long tripId) {
        cleanup();
        Job job = jobs.get(jobId);
        if (job == null || job.tripId != tripId) {
            return null;
        }
        return snapshot(job);
    }

    private PlanDtos.PlanningJobRead snapshot(Job job) {
        List<PlanDtos.PlaceRecommendation> recommendations = new ArrayList<>();
        for (String category : List.of("hotel", "restaurant", "attraction")) {
            List<Map<String, Object>> entries = job.categoryRecommendations.get(category);
            if (entries == null) {
                continue;
            }
            for (Map<String, Object> entry : entries) {
                recommendations.add(PlanningPipeline.toCard(entry));
            }
        }
        return new PlanDtos.PlanningJobRead(
                job.jobId,
                job.tripId,
                job.status,
                job.phase,
                Math.max(0, Math.min(100, job.progress)),
                List.copyOf(job.events),
                recommendations,
                job.result,
                job.error);
    }

    private void cleanup() {
        long cutoff = System.currentTimeMillis() - JOB_TTL_MILLIS;
        jobs.entrySet().removeIf(entry -> entry.getValue().updatedAt < cutoff);
    }

    /** 仅供测试/诊断：按更新时间倒序列出任务 id。 */
    public List<String> jobIds() {
        return jobs.values().stream()
                .sorted(Comparator.comparingLong((Job job) -> job.updatedAt).reversed())
                .map(job -> job.jobId)
                .toList();
    }
}
