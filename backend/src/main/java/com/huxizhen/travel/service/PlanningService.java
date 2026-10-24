package com.huxizhen.travel.service;

import com.huxizhen.travel.agent.planner.PlanningPipeline;
import com.huxizhen.travel.dto.plan.PlanDtos;
import com.huxizhen.travel.entity.ItineraryItem;
import com.huxizhen.travel.entity.Place;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.service.vector.VectorStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 规划入口：把数据库里的东西快照成 {@link PlanningPipeline.State}，跑完再把照片换成本地地址。
 *
 * <p>为什么先快照：流水线跑在 worker 线程上，那里没有数据库会话；而原文检索需要会话，
 * 所以它在这里完成，作为初始状态的一部分交给流水线。
 */
@Service
public class PlanningService {

    private final PlaceService placeService;
    private final ItineraryService itineraryService;
    private final VectorIndexService vectorIndexService;
    private final PhotoCacheService photoCacheService;
    private final PlanningPipeline pipeline;

    public PlanningService(
            PlaceService placeService,
            ItineraryService itineraryService,
            VectorIndexService vectorIndexService,
            PhotoCacheService photoCacheService,
            PlanningPipeline pipeline) {
        this.placeService = placeService;
        this.itineraryService = itineraryService;
        this.vectorIndexService = vectorIndexService;
        this.photoCacheService = photoCacheService;
        this.pipeline = pipeline;
    }

    public PlanningPipeline.State buildState(
            Trip trip,
            String query,
            boolean includeWeb,
            List<PlanDtos.SelectedRecommendation> selected,
            String adjustmentSummary) {
        PlanningPipeline.State state = new PlanningPipeline.State();
        state.trip = trip;
        state.places = placeService.listEntities(trip.getId());
        state.items = itineraryService.listEntities(trip.getId());
        state.query = query;
        state.includeWeb = includeWeb;
        state.selectedRecommendations = selected == null ? List.of() : selected;
        state.adjustmentSummary = adjustmentSummary;
        state.tripDays = (int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(
                trip.getStartDate(), trip.getEndDate()) + 1);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("adult_male_count", trip.getAdultMaleCount());
        profile.put("adult_female_count", trip.getAdultFemaleCount());
        profile.put("child_male_count", trip.getChildMaleCount());
        profile.put("child_female_count", trip.getChildFemaleCount());
        int adults = value(trip.getAdultMaleCount()) + value(trip.getAdultFemaleCount());
        int children = value(trip.getChildMaleCount()) + value(trip.getChildFemaleCount());
        profile.put("total_adults", adults);
        profile.put("total_children", children);
        profile.put("total_people", adults + children);
        state.travelerProfile = profile;

        // 旅客自己的订单/票据：检索一次，作为总结与推荐的依据。
        VectorIndexService.SearchOutcome outcome = vectorIndexService.search(trip.getId(), query, 5);
        if ("ok".equals(outcome.status())) {
            state.travelerDocuments = vectorIndexService.documentContext(outcome.chunks());
            List<String> sources = outcome.chunks().stream().map(VectorStore.Hit::source).distinct().toList();
            state.events.add(new PlanDtos.ToolEvent(
                    "document_search", "completed",
                    "已从上传资料中检索到 " + outcome.chunks().size() + " 段可用依据（来源："
                            + String.join("、", sources) + "）。"));
        } else {
            String status = List.of("empty", "skipped").contains(outcome.status()) ? "skipped" : "fallback";
            state.events.add(new PlanDtos.ToolEvent(
                    "document_search", status,
                    outcome.reason() == null ? "资料检索未完成，本次未使用上传资料。" : outcome.reason()));
        }
        return state;
    }

    public PlanDtos.TravelPlanRead createPlan(
            Trip trip,
            String query,
            boolean includeWeb,
            List<PlanDtos.SelectedRecommendation> selected,
            String adjustmentSummary) {
        PlanningPipeline.State state = buildState(trip, query, includeWeb, selected, adjustmentSummary);
        PlanDtos.TravelPlanRead plan = pipeline.run(state, null);
        return cachePlanPhotos(plan, includeWeb, trip.getDestination());
    }

    /**
     * 把外链图片换成本地副本；换不成就换成同源代理地址（代理失败时自己跳占位图）。
     * 这样浏览器永远不必直连图片 CDN。
     */
    public PlanDtos.TravelPlanRead cachePlanPhotos(
            PlanDtos.TravelPlanRead plan, boolean recoverMissing, String destination) {
        List<PlanDtos.PlaceRecommendation> recommendations = new ArrayList<>();
        for (PlanDtos.PlaceRecommendation card : plan.placeRecommendations()) {
            recommendations.add(withCachedImage(card));
        }
        List<PlanDtos.DailyPlan> days = new ArrayList<>();
        Map<String, String> categories = new LinkedHashMap<>();
        for (PlanDtos.PlaceRecommendation card : plan.placeRecommendations()) {
            categories.putIfAbsent(card.name(), card.category());
        }
        for (PlanDtos.DailyPlan day : plan.dailyPlan()) {
            List<PlanDtos.PlanItem> items = new ArrayList<>();
            for (PlanDtos.PlanItem item : day.items()) {
                items.add(new PlanDtos.PlanItem(
                        item.placeName(),
                        item.startTime(),
                        item.endTime(),
                        item.note(),
                        cachedUrl(item.imageUrl(), categories.getOrDefault(item.placeName(), "attraction")),
                        item.fallbackImageUrl()));
            }
            days.add(new PlanDtos.DailyPlan(day.date(), items, day.shortage()));
        }
        return new PlanDtos.TravelPlanRead(
                plan.tripId(),
                plan.summary(),
                plan.finalSummary(),
                plan.recommendations(),
                recommendations,
                plan.transportAdvice(),
                days,
                plan.budget(),
                plan.budgetPlan(),
                plan.conflicts(),
                plan.sources(),
                plan.events(),
                plan.travelerProfile(),
                plan.weather(),
                plan.weatherAdvice(),
                plan.weatherAdjustmentRequest(),
                plan.budgetSummary(),
                plan.handoff());
    }

    private PlanDtos.PlaceRecommendation withCachedImage(PlanDtos.PlaceRecommendation card) {
        String cached = cachedUrl(card.imageUrl(), card.category());
        return new PlanDtos.PlaceRecommendation(
                card.name(), card.category(), card.reason(), card.priceHint(), card.unitPrice(),
                card.priceUnit(), card.estimatedCost(), card.costExplanation(), card.quantity(),
                card.quantityUnit(), card.quantityMax(), card.costSource(), cached,
                card.fallbackImageUrl(), card.imageCredit(), card.imageCreditUrl(), card.latitude(),
                card.longitude(), card.rating(), card.signatureDish(), card.mealSlot(),
                card.overBudget(), card.budgetLimit(), card.budgetNote(), card.priceTier(),
                card.priceTierLabel(), card.priceAdjusted(), card.isFree(), card.pricePending());
    }

    private String cachedUrl(String url, String category) {
        if (url == null || url.isBlank() || url.startsWith("/static/")) {
            return url;
        }
        if (!url.startsWith("https://")) {
            return url;
        }
        String local = photoCacheService.cachePublicPhoto(url);
        if (local != null) {
            return local;
        }
        String proxied = PhotoCacheService.proxyPhotoUrl(url, category);
        return proxied != null ? proxied : url;
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
