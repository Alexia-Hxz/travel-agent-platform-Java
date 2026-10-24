package com.huxizhen.travel.agent.planner;

import com.huxizhen.travel.agent.tool.BudgetPlanGenerator;
import com.huxizhen.travel.agent.tool.BudgetPlanTool;
import com.huxizhen.travel.agent.tool.BudgetTool;
import com.huxizhen.travel.agent.tool.ConflictDetector;
import com.huxizhen.travel.agent.tool.PlaceImageResolver;
import com.huxizhen.travel.agent.tool.PlanSummaryGenerator;
import com.huxizhen.travel.agent.tool.RecommendationService;
import com.huxizhen.travel.agent.tool.WeatherAdvisor;
import com.huxizhen.travel.dto.plan.PlanDtos;
import com.huxizhen.travel.entity.ItineraryItem;
import com.huxizhen.travel.entity.Place;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.integration.search.WebSearchService;
import com.huxizhen.travel.integration.weather.WeatherService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 规划流水线：6 个节点、纯线性（与原 LangGraph 的 {@code add_edge} 序列一一对应）。
 *
 * <pre>
 * load_context → live_search → recommend_places → analyze_budget_and_conflicts
 *              → generate_final_summary → compose_plan
 * </pre>
 *
 * <p>图片那一趟是唯一并行的部分：它纯网络、耗时 15-25s，而它之后没有任何节点需要图片，
 * 所以放在单独线程里跑，到 {@code compose_plan} 才 join（180s 上限，超时就给占位图）。
 * 这样照片的等待被后面的冲突检查、预算和文字总结盖住了，而不是叠加在前面。
 */
@Service
public class PlanningPipeline {

    private static final Logger log = LoggerFactory.getLogger(PlanningPipeline.class);
    private static final long IMAGE_JOIN_TIMEOUT_SECONDS = 180;
    private static final Pattern ROUTE_TARGET_PATTERN =
            Pattern.compile("(?:怎么|如何|怎样)?(?:到|去)\\s*([^，。！？?；;]+)");
    private static final Pattern ROUTE_TARGET_STOP =
            Pattern.compile("(?:性价比|最划算|最便宜|最快|路线|交通|怎么走|如何走)");
    private static final List<String> ROUTE_KEYWORDS =
            List.of("怎么到", "如何到", "怎样到", "到", "去", "交通", "路线");
    private static final ExecutorService IMAGE_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "place-images");
        thread.setDaemon(true);
        return thread;
    });

    private final WebSearchService webSearchService;
    private final RecommendationService recommendationService;
    private final PlanSummaryGenerator summaryGenerator;
    private final PlaceImageResolver imageResolver;
    private final WeatherService weatherService;
    private final BudgetPlanGenerator budgetPlanGenerator;

    public PlanningPipeline(
            WebSearchService webSearchService,
            RecommendationService recommendationService,
            PlanSummaryGenerator summaryGenerator,
            PlaceImageResolver imageResolver,
            WeatherService weatherService,
            BudgetPlanGenerator budgetPlanGenerator) {
        this.webSearchService = webSearchService;
        this.recommendationService = recommendationService;
        this.summaryGenerator = summaryGenerator;
        this.imageResolver = imageResolver;
        this.weatherService = weatherService;
        this.budgetPlanGenerator = budgetPlanGenerator;
    }

    /** 进度回调：plan-jobs 靠它把 phase/progress 推给前端。 */
    public interface ProgressListener {
        void onCategory(Map<String, Object> update);

        void onEvent(PlanDtos.ToolEvent event, String phase, int progress);
    }

    /** 一次规划的输入与中间结果。可变，因为节点之间要传递。 */
    public static class State {
        public Trip trip;
        public List<Place> places = List.of();
        public List<ItineraryItem> items = List.of();
        public String query = "";
        public boolean includeWeb;
        public String routeTarget;
        public List<PlanDtos.SelectedRecommendation> selectedRecommendations = List.of();
        public String adjustmentSummary;
        public List<Map<String, Object>> travelerDocuments = List.of();
        public Map<String, Object> travelerProfile = Map.of();
        public int tripDays = 1;

        public List<PlanDtos.ToolEvent> events = new ArrayList<>();
        public List<Map<String, Object>> webResults = List.of();
        public List<Map<String, Object>> recommendedPlaces = List.of();
        public List<PlanDtos.DailyPlan> generatedDailyPlan = List.of();
        public Map<String, Object> imageMap = Map.of();
        public List<ConflictDetector.Conflict> conflicts = List.of();
        public BudgetTool.Estimate budget;
        public PlanDtos.BudgetPlanRead budgetPlan;
        public Map<String, Object> weather = Map.of();
        public List<PlanDtos.WeatherAdvice> weatherAdvice = List.of();
        public String finalSummary = "";
        public PlanDtos.TravelPlanRead response;
    }

    public PlanDtos.TravelPlanRead run(State state, ProgressListener listener) {
        loadContext(state);
        liveSearch(state, listener);
        CompletableFuture<Void> images = recommendPlaces(state, listener);
        analyze(state, listener);
        summarize(state, listener);
        compose(state, images, listener);
        return state.response;
    }

    // ------------------------------------------------------------- 1. context

    private void loadContext(State state) {
        StringBuilder message = new StringBuilder("已加载旅行项目、收藏地点和每日行程。");
        if (!state.travelerDocuments.isEmpty()) {
            message.append("并载入 ").append(state.travelerDocuments.size()).append(" 段来自旅客上传资料的依据。");
        }
        addEvent(state, "context_loader", "completed", message.toString());
    }

    // -------------------------------------------------------------- 2. search

    private void liveSearch(State state, ProgressListener listener) {
        if (!state.includeWeb) {
            addEvent(state, "live_search", "skipped", "用户未启用联网搜索。");
            notifyEvent(state, listener);
            return;
        }
        state.routeTarget = parseRouteTarget(state.query);
        String query = routeQuery(state);
        WebSearchService.SearchResult result = webSearchService.searchWeb(query, state.trip.getDestination());
        state.webResults = result.results();
        String status = "completed".equals(result.status()) ? "completed" : "fallback";
        addEvent(state, "live_search", status, result.status());
        notifyEvent(state, listener);
    }

    private String routeQuery(State state) {
        String query = state.query == null ? "" : state.query;
        Place hotel = firstHotel(state);
        if (hotel != null && state.routeTarget != null) {
            return "从" + hotel.getName() + "（" + hotel.getAddress() + "）到" + state.routeTarget
                    + " 性价比最高 公共交通 地铁 公交 时间 票价";
        }
        return query + " 酒店 景点 美食 图片";
    }

    /**
     * 从"怎么到迪士尼""去环球影城怎么走"里取真正的目的地。
     * 取不到就返回 null——此时不做交通建议，也不会把整趟旅行的目的地当成问句目的地。
     */
    static String parseRouteTarget(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        boolean route = false;
        for (String keyword : ROUTE_KEYWORDS) {
            if (query.contains(keyword)) {
                route = true;
                break;
            }
        }
        if (!route) {
            return null;
        }
        Matcher matcher = ROUTE_TARGET_PATTERN.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        String target = matcher.group(1);
        Matcher stop = ROUTE_TARGET_STOP.matcher(target);
        if (stop.find()) {
            target = target.substring(0, stop.start());
        }
        target = target.replaceAll("^[\\s：:，。！？?；;]+", "")
                .replaceAll("[\\s：:，。！？?；;]+$", "");
        return target.isBlank() ? null : target;
    }

    private static Place firstHotel(State state) {
        return state.places.stream()
                .filter(place -> "hotel".equals(place.getType()))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------- 3. recommendations

    private CompletableFuture<Void> recommendPlaces(State state, ProgressListener listener) {
        Map<String, Integer> allowances =
                RecommendationService.budgetAllowances(state.trip.getBudget(), state.tripDays, state.travelerProfile);
        Map<String, Integer> limits = RecommendationService.categoryLimits(state.tripDays);

        RecommendationService.Extraction extraction =
                recommendationService.extract(
                        state.trip.getDestination(), state.webResults, allowances, limits);
        state.recommendedPlaces = new ArrayList<>(extraction.places());

        // 分类卡片：先让地图和列表尽快有东西看，价格稍后补。
        publishCategories(listener, state.recommendedPlaces);
        if (!"skipped".equals(extraction.status())) {
            state.recommendedPlaces = recommendationService.ensureCategoryCoverage(
                    state.trip.getDestination(),
                    state.recommendedPlaces,
                    allowances,
                    limits,
                    (category, entries) -> publishCategory(listener, category, entries));
        }

        // 顺序与 Python 版一致：比价 → 事实补全 → 定价 → 档位铺开 → 再定价。
        recommendationService.enrichPricesWithSearch(
                state.recommendedPlaces, state.trip.getDestination());
        recommendationService.enrichPlaceFacts(
                state.recommendedPlaces, state.trip.getDestination());
        RecommendationService.estimateCosts(
                state.recommendedPlaces, state.tripDays, state.travelerProfile,
                state.trip.getBudget(), state.trip.getDestination());
        List<Map<String, Object>> spread = recommendationService.ensurePriceSpread(
                state.trip.getDestination(),
                state.recommendedPlaces,
                state.trip.getBudget(),
                state.tripDays,
                state.travelerProfile);
        if (!spread.isEmpty()) {
            recommendationService.enrichPricesWithSearch(spread, state.trip.getDestination());
            RecommendationService.estimateCosts(
                    state.recommendedPlaces, state.tripDays, state.travelerProfile,
                    state.trip.getBudget(), state.trip.getDestination());
        }

        String message = switch (extraction.status()) {
            case "completed" -> "已从检索资料中提取 " + state.recommendedPlaces.size() + " 个具体推荐地点并配图。";
            case "fallback" -> "未使用大模型，已按关键词整理 " + state.recommendedPlaces.size() + " 个推荐地点并配图。";
            default -> "检索资料不足，暂无可提取的推荐地点。";
        };
        addEvent(state, "place_recommender", extraction.status(), message);
        notifyEvent(state, listener);

        state.generatedDailyPlan = generatedDailyPlan(state);
        List<Map<String, Object>> planned = plannedPlaceEntries(state);
        CompletableFuture<Void> future = CompletableFuture.supplyAsync(
                () -> {
                    List<Map<String, Object>> picturedPlanned = imageResolver.resolve(
                            new ArrayList<>(planned), state.trip.getDestination(), Set.of(), 40);
                    List<Map<String, Object>> pictured = imageResolver.resolve(
                            new ArrayList<>(state.recommendedPlaces), state.trip.getDestination(), Set.of("hotel"), 40);
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (Map<String, Object> entry : picturedPlanned) {
                        putImage(map, entry);
                    }
                    for (Map<String, Object> entry : pictured) {
                        putImage(map, entry);
                    }
                    return new ImagePass(pictured, map);
                },
                IMAGE_EXECUTOR).thenAccept(pass -> {
                    state.recommendedPlaces = pass.places();
                    state.imageMap = pass.imageMap();
                });
        return future.exceptionally(error -> {
            log.warn("the picture pass did not finish; serving the plan without its photos", error);
            return null;
        });
    }

    private static void putImage(Map<String, Object> map, Map<String, Object> entry) {
        String name = String.valueOf(entry.get("name"));
        Object image = entry.get("image_url");
        if (name.isBlank() || image == null || map.containsKey(name)) {
            return;
        }
        Map<String, Object> imageEntry = new LinkedHashMap<>();
        imageEntry.put("image_url", image);
        imageEntry.put("fallback_image_url", entry.get("fallback_image_url"));
        map.put(name, imageEntry);
    }

    private void publishCategories(ProgressListener listener, List<Map<String, Object>> places) {
        for (String category : List.of("hotel", "restaurant", "attraction")) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map<String, Object> place : places) {
                if (category.equals(String.valueOf(place.get("type")))) {
                    entries.add(place);
                }
            }
            if (!entries.isEmpty()) {
                publishCategory(listener, category, entries);
            }
        }
    }

    private void publishCategory(
            ProgressListener listener, String category, List<Map<String, Object>> entries) {
        if (listener == null || entries == null || entries.isEmpty()) {
            return;
        }
        Map<String, Object> update = new LinkedHashMap<>();
        update.put("kind", "category");
        update.put("category", category);
        update.put("recommendations", entries.stream().map(this::cardOf).toList());
        listener.onCategory(update);
    }

    private record ImagePass(List<Map<String, Object>> places, Map<String, Object> imageMap) {
    }

    private Map<String, Object> cardOf(Map<String, Object> place) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", place.getOrDefault("name", ""));
        card.put("category", place.get("type"));
        card.put("reason", place.get("reason"));
        card.put("meal_slot", place.get("meal_slot"));
        card.put("image_url", place.get("image_url"));
        card.put("fallback_image_url", place.get("fallback_image_url"));
        card.put("latitude", place.get("latitude"));
        card.put("longitude", place.get("longitude"));
        card.put("price_pending", true);
        return card;
    }

    /** 已排进行程的地点：图片线程也要给它们配图，否则时间线里只有推荐卡片有照片。 */
    private static List<Map<String, Object>> plannedPlaceEntries(State state) {
        Map<Long, Place> byId = new LinkedHashMap<>();
        for (Place place : state.places) {
            byId.put(place.getId(), place);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ItineraryItem item : state.items) {
            Place place = byId.get(item.getPlaceId());
            if (place == null || place.getName() == null || place.getName().isBlank()) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", place.getName());
            entry.put("type", place.getType() == null ? "attraction" : place.getType());
            entry.put("image_url", place.getImageUrl());
            entries.add(entry);
        }
        return entries;
    }

    // ------------------------------------------------------------ 4. analysis

    private void analyze(State state, ProgressListener listener) {
        List<Map<String, Object>> itinerary = itineraryMaps(state);
        state.conflicts = ConflictDetector.detect(itinerary);
        addEvent(state, "conflict_checker", "completed", "检测到 " + state.conflicts.size() + " 个时间冲突。");
        notifyEvent(state, listener);

        state.budget = BudgetTool.estimateBudget(placeMaps(state), state.tripDays, state.trip.getBudget(), state.travelerProfile);
        addEvent(state, "budget_estimator", "completed", "已完成预算估算。");
        notifyEvent(state, listener);

        state.budgetPlan = budgetPlanGenerator.build(
                state.trip.getBudget(),
                state.tripDays,
                state.travelerProfile,
                state.trip.getDestination());
        if ("estimated".equals(state.budgetPlan.status())) {
            addEvent(state, "budget_planner", "completed",
                    "已按总预算 " + state.budgetPlan.tripBudget() + " CNY 给出分类预算建议（计划花掉 "
                            + state.budgetPlan.plannedTotal() + " CNY）。");
            notifyEvent(state, listener);
        }

        state.weather = weatherService.getForecast(
                state.trip.getDestination(),
                state.trip.getStartDate().toString(),
                state.trip.getEndDate().toString());
        addEvent(state, "weather_forecast",
                String.valueOf(state.weather.getOrDefault("status", "fallback")),
                "completed".equals(state.weather.get("status"))
                        ? "已查询旅行日期天气预报。"
                        : String.valueOf(state.weather.getOrDefault("message", "天气预报不可用。")));
        notifyEvent(state, listener);

        state.weatherAdvice = WeatherAdvisor.build(
                state.trip.getDestination(),
                state.weather,
                scheduledDays(state),
                placeMaps(state));
        if (!state.weatherAdvice.isEmpty()) {
            addEvent(state, "weather_advisor", "completed",
                    "发现 " + state.weatherAdvice.size() + " 天天气可能影响行程安排。");
            notifyEvent(state, listener);
        }
    }

    // ------------------------------------------------------------- 5. summary

    private void summarize(State state, ProgressListener listener) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("request", state.query);
        context.put("trip", tripMap(state.trip));
        context.put("saved_places", placeMaps(state));
        context.put("saved_place_count", state.places.size());
        context.put("itinerary", itineraryMaps(state));
        context.put("transport_advice", transportAdvice(state));
        context.put("budget", budgetMap(state.budget));
        context.put("budget_plan", state.budgetPlan);
        context.put("budget_plan_summary", BudgetPlanTool.formatSummary(state.budgetPlan));
        context.put("conflicts", state.conflicts.stream().map(ConflictDetector.Conflict::message).toList());
        context.put("weather_advice", state.weatherAdvice);
        context.put("web_results", state.webResults.subList(0, Math.min(3, state.webResults.size())));
        context.put("traveler_profile", state.travelerProfile);
        context.put("weather", state.weather);
        context.put("budget_summary", BudgetTool.budgetSummary(state.budget));
        context.put("itemized_summary", state.budget.itemizedSummary());
        context.put("generated_daily_plan", state.generatedDailyPlan);
        context.put("recommendations", state.recommendedPlaces);
        context.put("selected_recommendations", state.selectedRecommendations);
        context.put("adjustment_summary", state.adjustmentSummary);
        context.put("traveler_documents", state.travelerDocuments);

        String[] result = summaryGenerator.generate(context);
        state.finalSummary = result[0];
        addEvent(state, "final_summary_generator", result[1],
                "completed".equals(result[1]) ? "已生成最终旅行总结。" : "已使用本地规则生成最终旅行总结。");
        notifyEvent(state, listener);
    }

    // ------------------------------------------------------------- 6. compose

    private void compose(State state, CompletableFuture<Void> images, ProgressListener listener) {
        try {
            images.get(IMAGE_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception error) {
            log.warn("the picture pass did not finish; serving the plan without its photos", error);
            // 与 Python 一致：拿不到照片时退回内置占位图，但字段不能空着。
            state.recommendedPlaces = imageResolver.resolve(
                    new ArrayList<>(state.recommendedPlaces), state.trip.getDestination(), Set.of("hotel"), 0);
        }
        applyImagesToGeneratedPlan(state);
        state.response = buildResponse(state);
    }

    /** 建议的每日安排是在图片线程启动前拼好的，这里把图片按地点名回填，与 Python 的 compose 一致。 */
    private static void applyImagesToGeneratedPlan(State state) {
        if (state.generatedDailyPlan.isEmpty() || state.imageMap.isEmpty()) {
            return;
        }
        List<PlanDtos.DailyPlan> updated = new ArrayList<>();
        for (PlanDtos.DailyPlan day : state.generatedDailyPlan) {
            List<PlanDtos.PlanItem> items = new ArrayList<>();
            for (PlanDtos.PlanItem item : day.items()) {
                items.add(withImage(item, state.imageMap));
            }
            updated.add(new PlanDtos.DailyPlan(day.date(), items, day.shortage()));
        }
        state.generatedDailyPlan = updated;
    }

    private static PlanDtos.PlanItem withImage(
            PlanDtos.PlanItem item, Map<String, Object> imageMap) {
        Object value = imageMap.get(item.placeName());
        if (!(value instanceof Map<?, ?> map)) {
            return item;
        }
        String imageUrl = map.get("image_url") == null ? item.imageUrl() : String.valueOf(map.get("image_url"));
        String fallbackUrl = map.get("fallback_image_url") == null
                ? item.fallbackImageUrl()
                : String.valueOf(map.get("fallback_image_url"));
        return new PlanDtos.PlanItem(
                item.placeName(), item.startTime(), item.endTime(), item.note(), imageUrl, fallbackUrl);
    }

    private PlanDtos.TravelPlanRead buildResponse(State state) {
        List<PlanDtos.DailyPlan> dailyPlan = dailyPlan(state);
        List<PlanDtos.PlanSource> sources = new ArrayList<>();
        for (Place place : state.places) {
            sources.add(new PlanDtos.PlanSource(
                    "saved_place", place.getName(), place.getSourceUrl(), place.getAddress(), null, place.getImageUrl()));
        }
        for (Map<String, Object> result : state.webResults) {
            sources.add(new PlanDtos.PlanSource(
                    String.valueOf(result.get("source_type")),
                    String.valueOf(result.get("title")),
                    result.get("url") == null ? null : String.valueOf(result.get("url")),
                    String.valueOf(result.get("snippet")),
                    result.get("score") instanceof Number number ? number.doubleValue() : null,
                    result.get("image_url") == null ? null : String.valueOf(result.get("image_url"))));
        }

        List<PlanDtos.PlaceRecommendation> cards = new ArrayList<>();
        for (Map<String, Object> place : state.recommendedPlaces) {
            cards.add(toCard(place));
        }

        PlanDtos.PlanHandoff handoff = handoff(state);
        String summaryLead = "已根据 " + state.trip.getDestination() + " 的收藏地点和行程生成规划。";
        if (handoff.savedPlaceCount() > 0) {
            summaryLead += "你收藏的 " + handoff.savedPlaceCount() + " 个地点已全部纳入本次总结。";
        }
        if (!handoff.selectedRecommendations().isEmpty()) {
            summaryLead += "本次总结已纳入你在规划建议中选中的 " + handoff.selectedRecommendations().size()
                    + " 个项目（合计约 " + handoff.selectedTotal() + " CNY）。";
        }
        if (handoff.hasAdjustmentSummary()) {
            summaryLead += "对话式调整行程的结论也已合并进本次总结。";
        }

        return new PlanDtos.TravelPlanRead(
                state.trip.getId(),
                summaryLead,
                state.finalSummary,
                state.webResults.stream().map(PlanningPipeline::toSource).toList(),
                cards,
                transportAdvice(state),
                dailyPlan,
                toBudgetRead(state.budget),
                state.budgetPlan,
                state.conflicts.stream()
                        .map(conflict -> new PlanDtos.ConflictRead(
                                conflict.date(), conflict.firstPlace(), conflict.secondPlace(), conflict.message()))
                        .toList(),
                sources,
                List.copyOf(state.events),
                state.travelerProfile,
                state.weather,
                state.weatherAdvice,
                WeatherAdvisor.adjustmentRequest(state.trip.getDestination(), state.weatherAdvice),
                BudgetTool.budgetSummary(state.budget),
                handoff);
    }

    private PlanDtos.PlanHandoff handoff(State state) {
        List<PlanDtos.SelectedRecommendation> selected = new ArrayList<>();
        int total = 0;
        for (PlanDtos.SelectedRecommendation item : state.selectedRecommendations) {
            String name = item.name() == null ? "" : item.name().strip();
            if (name.isEmpty()) {
                continue;
            }
            String category = item.category() == null || item.category().isBlank()
                    ? "attraction"
                    : item.category();
            selected.add(new PlanDtos.SelectedRecommendation(
                    name, category, item.estimatedCost(), item.reason()));
            total += item.estimatedCost() == null ? 0 : item.estimatedCost();
        }
        List<String> names = state.places.stream().map(Place::getName).filter(name -> name != null && !name.isBlank()).toList();
        return new PlanDtos.PlanHandoff(
                selected,
                total,
                state.adjustmentSummary != null && !state.adjustmentSummary.isBlank(),
                state.places.size(),
                names);
    }

    private PlanDtos.TransportAdvice transportAdvice(State state) {
        if (state.routeTarget == null) {
            return null;
        }
        Place hotel = firstHotel(state);
        if (hotel == null) {
            return null;
        }
        String answer = null;
        for (Map<String, Object> result : state.webResults) {
            if ("web_answer".equals(result.get("source_type"))) {
                answer = String.valueOf(result.get("snippet"));
                break;
            }
        }
        return new PlanDtos.TransportAdvice(
                hotel.getName() + "（" + hotel.getAddress() + "）",
                state.routeTarget,
                answer == null ? "未获得可核验的联网交通结果，请重试后再确认实时票价与时刻。" : answer);
    }

    private PlanDtos.DailyPlan toDaily(PlanDtos.DailyPlan source) {
        return source;
    }

    // ------------------------------------------------------------- daily plan

    /**
     * 为**每一天**给一份建议安排，且每个地点整趟只用一次：否则五天行程会把同一个景点
     * 排五天，酒店房价还会被当成一天的消费。
     */
    List<PlanDtos.DailyPlan> generatedDailyPlan(State state) {
        String query = state.query == null ? "" : state.query;
        boolean wanted = List.of("推荐", "规划", "第一天", "酒店", "景点", "美食").stream().anyMatch(query::contains);
        if (state.recommendedPlaces.isEmpty() || !wanted) {
            return List.of();
        }
        List<LocalDate> dates = tripDates(state.trip);
        if (dates.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> hotels = byType(state.recommendedPlaces, "hotel");
        List<Map<String, Object>> attractions = byType(state.recommendedPlaces, "attraction");
        List<Map<String, Object>> restaurants = byType(state.recommendedPlaces, "restaurant");
        List<Map<String, Object>> breakfasts = restaurants.stream()
                .filter(place -> "breakfast".equals(place.get("meal_slot")))
                .toList();
        if (breakfasts.isEmpty()) {
            // 与 Python 一致：只退到 any，不再把全部餐厅当早餐，否则午餐/晚餐会被早餐占掉。
            breakfasts = restaurants.stream().filter(place -> "any".equals(place.get("meal_slot"))).toList();
        }
        List<Map<String, Object>> mains = restaurants.stream()
                .filter(place -> !"breakfast".equals(place.get("meal_slot")))
                .toList();
        if (mains.isEmpty()) {
            mains = restaurants;
        }
        int nights = Math.max(0, dates.size() - 1);
        List<String[]> used = new ArrayList<>();

        List<PlanDtos.DailyPlan> plan = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            List<PlanDtos.PlanItem> items = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            if (index == 0 && !hotels.isEmpty()) {
                Map<String, Object> hotel = hotels.get(0);
                used.add(new String[] {"hotel", String.valueOf(hotel.get("name")).strip()});
                Object cost = hotel.get("estimated_cost");
                String note = nights > 0 && cost != null
                        ? "办理入住；住宿费约 " + cost + " 元是全程 " + nights + " 晚的合计，不是当天消费"
                        : "办理入住或先寄存行李";
                items.add(new PlanDtos.PlanItem(
                        String.valueOf(hotel.get("name")), "13:00:00", "14:00:00", note,
                        hotel.get("image_url") == null ? null : String.valueOf(hotel.get("image_url")),
                        hotel.get("fallback_image_url") == null ? null : String.valueOf(hotel.get("fallback_image_url"))));
            }
            Map<String, Object> breakfast = take("restaurant", breakfasts, used);
            if (breakfast != null) {
                items.add(item(breakfast, "08:00:00", "09:00:00", "当地早餐"));
            } else {
                missing.add("早餐");
            }
            Map<String, Object> morning = take("attraction", attractions, used);
            if (morning != null) {
                items.add(item(morning, "09:30:00", "12:00:00", "上午主要游览，预留交通与拍照时间"));
            } else {
                missing.add("景点");
            }
            Map<String, Object> lunch = take("restaurant", mains, used);
            if (lunch != null) {
                items.add(item(lunch, "12:00:00", "13:00:00", "当地特色午餐"));
            } else {
                missing.add("午餐");
            }
            Map<String, Object> afternoon = take("attraction", attractions, used);
            if (afternoon != null) {
                items.add(item(afternoon, "14:00:00", "17:00:00", "下午继续游览，按现场人流灵活调整"));
            }
            Map<String, Object> dinner = take("restaurant", mains, used);
            if (dinner != null) {
                items.add(item(dinner, "18:00:00", "19:30:00", "晚餐"));
            } else {
                missing.add("晚餐");
            }
            items.sort(Comparator.comparing(PlanDtos.PlanItem::startTime));
            plan.add(new PlanDtos.DailyPlan(
                    dates.get(index),
                    items,
                    missing.isEmpty()
                            ? null
                            : "推荐项目不够分配，本日缺少" + String.join("、", missing)
                                    + "；请再多生成一次规划建议补充候选。"));
        }
        return plan;
    }

    private static PlanDtos.PlanItem item(
            Map<String, Object> place, String start, String end, String note) {
        return new PlanDtos.PlanItem(
                String.valueOf(place.get("name")), start, end, note,
                place.get("image_url") == null ? null : String.valueOf(place.get("image_url")),
                place.get("fallback_image_url") == null ? null : String.valueOf(place.get("fallback_image_url")));
    }

    /**
     * 取一个还没用过的地点。名字互相包含且差异不是纯数字时视为同一家
     * （"蒋有记" vs "老门东内蒋有记"）；酒店不参与包含比较，否则"夫子庙…酒店"会让"夫子庙"消失。
     */
    private static Map<String, Object> take(
            String category, List<Map<String, Object>> pool, List<String[]> used) {
        for (Map<String, Object> candidate : pool) {
            String name = String.valueOf(candidate.getOrDefault("name", "")).strip();
            if (name.isEmpty()) {
                continue;
            }
            boolean taken = false;
            for (String[] other : used) {
                if (!"hotel".equals(other[0]) && sameVenue(name, other[1])) {
                    taken = true;
                    break;
                }
                if (name.equalsIgnoreCase(other[1])) {
                    taken = true;
                    break;
                }
            }
            if (taken) {
                continue;
            }
            used.add(new String[] {category, name});
            return candidate;
        }
        return null;
    }

    /** 两个名字是不是同一家店的不同写法；纯数字差异（景点1/景点10）不算。 */
    static boolean sameVenue(String first, String second) {
        String left = first == null ? "" : first.strip().toLowerCase();
        String right = second == null ? "" : second.strip().toLowerCase();
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        String shorter = left.length() <= right.length() ? left : right;
        String longer = left.length() <= right.length() ? right : left;
        if (!longer.contains(shorter)) {
            return false;
        }
        String remainder = longer.replaceFirst(
                java.util.regex.Pattern.quote(shorter), java.util.regex.Matcher.quoteReplacement(""));
        return !remainder.matches("\\d+");
    }

    private static List<Map<String, Object>> byType(List<Map<String, Object>> places, String type) {
        return places.stream().filter(place -> type.equals(place.get("type"))).toList();
    }

    private static List<LocalDate> tripDates(Trip trip) {
        List<LocalDate> dates = new ArrayList<>();
        if (trip.getStartDate() == null || trip.getEndDate() == null
                || trip.getEndDate().isBefore(trip.getStartDate())) {
            return dates;
        }
        for (LocalDate cursor = trip.getStartDate(); !cursor.isAfter(trip.getEndDate()); cursor = cursor.plusDays(1)) {
            dates.add(cursor);
        }
        return dates;
    }

    // --------------------------------------------------------------- helpers

    List<PlanDtos.DailyPlan> dailyPlan(State state) {
        List<Map<String, Object>> itinerary = itineraryMaps(state);
        if (itinerary.isEmpty()) {
            return state.generatedDailyPlan;
        }
        Map<Long, Place> placeById = new LinkedHashMap<>();
        for (Place place : state.places) {
            placeById.put(place.getId(), place);
        }
        Map<String, List<PlanDtos.PlanItem>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> item : itinerary) {
            String date = String.valueOf(item.get("date"));
            Map<String, Object> image = state.imageMap.get(String.valueOf(item.get("place_name"))) instanceof Map<?, ?> map
                    ? castMap(map)
                    : Map.of();
            Place place = item.get("place_id") instanceof Number number
                    ? placeById.get(number.longValue())
                    : null;
            String imageUrl = image.get("image_url") != null
                    ? String.valueOf(image.get("image_url"))
                    : (place == null ? null : place.getImageUrl());
            String fallbackUrl = image.get("fallback_image_url") == null
                    ? null
                    : String.valueOf(image.get("fallback_image_url"));
            grouped.computeIfAbsent(date, key -> new ArrayList<>()).add(new PlanDtos.PlanItem(
                    String.valueOf(item.get("place_name")),
                    String.valueOf(item.get("start_time")),
                    String.valueOf(item.get("end_time")),
                    item.get("note") == null ? null : String.valueOf(item.get("note")),
                    imageUrl,
                    fallbackUrl));
        }
        List<PlanDtos.DailyPlan> plan = new ArrayList<>();
        grouped.forEach((date, items) -> {
            items.sort(Comparator.comparing(PlanDtos.PlanItem::startTime));
            plan.add(new PlanDtos.DailyPlan(LocalDate.parse(date), items, null));
        });
        plan.sort(Comparator.comparing(PlanDtos.DailyPlan::date));
        return plan;
    }

    private List<Map<String, Object>> scheduledDays(State state) {
        List<Map<String, Object>> itinerary = itineraryMaps(state);
        if (itinerary.isEmpty()) {
            List<Map<String, Object>> days = new ArrayList<>();
            for (PlanDtos.DailyPlan day : state.generatedDailyPlan) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("date", day.date().toString());
                entry.put("items", day.items().stream()
                        .map(item -> Map.of("place_name", item.placeName()))
                        .toList());
                days.add(entry);
            }
            return days;
        }
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> item : itinerary) {
            grouped.computeIfAbsent(String.valueOf(item.get("date")), key -> new ArrayList<>())
                    .add(String.valueOf(item.get("place_name")));
        }
        List<Map<String, Object>> days = new ArrayList<>();
        grouped.keySet().stream().sorted().forEach(date -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", date);
            entry.put("items", grouped.get(date).stream()
                    .map(name -> Map.of("place_name", name))
                    .toList());
            days.add(entry);
        });
        return days;
    }

    List<Map<String, Object>> placeMaps(State state) {
        List<Map<String, Object>> places = new ArrayList<>();
        for (Place place : state.places) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", place.getId());
            entry.put("name", place.getName());
            entry.put("type", place.getType());
            entry.put("address", place.getAddress());
            entry.put("latitude", place.getLatitude());
            entry.put("longitude", place.getLongitude());
            entry.put("image_url", place.getImageUrl());
            entry.put("rating", place.getRating());
            entry.put("price_level", place.getPriceLevel());
            entry.put("opening_hours", place.getOpeningHours());
            entry.put("source_url", place.getSourceUrl());
            entry.put("note", place.getNote());
            entry.put("quoted_cost", place.getEstimatedCost());
            entry.put("quantity", place.getQuantity());
            places.add(entry);
        }
        return places;
    }

    List<Map<String, Object>> itineraryMaps(State state) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (ItineraryItem item : state.items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.getId());
            entry.put("date", item.getDate().toString());
            entry.put("start_time", item.getStartTime() == null ? null : item.getStartTime().toString());
            entry.put("end_time", item.getEndTime() == null ? null : item.getEndTime().toString());
            entry.put("place_id", item.getPlaceId());
            entry.put("place_name", placeName(state, item.getPlaceId()));
            entry.put("note", item.getNote());
            entry.put("sort_order", item.getSortOrder());
            items.add(entry);
        }
        return items;
    }

    private static String placeName(State state, Long placeId) {
        return state.places.stream()
                .filter(place -> place.getId().equals(placeId))
                .map(Place::getName)
                .findFirst()
                .orElse("");
    }

    private static Map<String, Object> tripMap(Trip trip) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", trip.getId());
        map.put("destination", trip.getDestination());
        map.put("start_date", trip.getStartDate() == null ? null : trip.getStartDate().toString());
        map.put("end_date", trip.getEndDate() == null ? null : trip.getEndDate().toString());
        map.put("budget", trip.getBudget());
        map.put("title", trip.getTitle());
        return map;
    }

    static Map<String, Object> budgetMap(BudgetTool.Estimate estimate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total_estimate", estimate.totalEstimate());
        map.put("trip_budget", estimate.tripBudget());
        map.put("remaining_budget", estimate.remainingBudget());
        map.put("currency", estimate.currency());
        map.put("note", estimate.note());
        map.put("breakdown", estimate.breakdown());
        map.put("daily_estimate", estimate.dailyEstimate());
        map.put("budget_summary", BudgetTool.budgetSummary(estimate));
        map.put("itemized_summary", estimate.itemizedSummary());
        map.put("excluded_allowances", estimate.excludedAllowances());
        return map;
    }

    static PlanDtos.BudgetRead toBudgetRead(BudgetTool.Estimate estimate) {
        return new PlanDtos.BudgetRead(
                estimate.totalEstimate(),
                estimate.tripBudget(),
                estimate.remainingBudget(),
                estimate.currency(),
                estimate.note(),
                estimate.breakdown(),
                estimate.dailyEstimate(),
                BudgetTool.budgetSummary(estimate),
                estimate.itemizedSummary(),
                estimate.excludedAllowances());
    }

    static List<Map<String, Object>> conflictMaps(List<ConflictDetector.Conflict> conflicts) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (ConflictDetector.Conflict conflict : conflicts) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", conflict.date().toString());
            map.put("first_place", conflict.firstPlace());
            map.put("second_place", conflict.secondPlace());
            map.put("message", conflict.message());
            maps.add(map);
        }
        return maps;
    }

    private static PlanDtos.PlanSource toSource(Map<String, Object> webResult) {
        return new PlanDtos.PlanSource(
                String.valueOf(webResult.get("source_type")),
                String.valueOf(webResult.get("title")),
                webResult.get("url") == null ? null : String.valueOf(webResult.get("url")),
                String.valueOf(webResult.get("snippet")),
                webResult.get("score") instanceof Number number ? number.doubleValue() : null,
                webResult.get("image_url") == null ? null : String.valueOf(webResult.get("image_url")));
    }

    public static PlanDtos.PlaceRecommendation toCard(Map<String, Object> place) {
        // 轮询快照里的卡片（cardOf）用 category，最终卡片（estimateCosts）用 type；两者都要认。
        String category = textOf(place.get("category"));
        if (category == null || category.isBlank()) {
            category = textOf(place.get("type"));
        }
        if (category == null || category.isBlank()) {
            category = "attraction";
        }
        return new PlanDtos.PlaceRecommendation(
                String.valueOf(place.getOrDefault("name", "")),
                category,
                textOf(place.get("reason")),
                textOf(place.get("price_hint")),
                intOf(place.get("unit_price")),
                textOf(place.get("price_unit")),
                intOf(place.get("estimated_cost")),
                textOf(place.get("cost_explanation")),
                intOf(place.get("quantity")) == null ? 1 : intOf(place.get("quantity")),
                textOf(place.get("quantity_unit")),
                intOf(place.get("quantity_max")),
                textOf(place.get("cost_source")),
                textOf(place.get("image_url")),
                textOf(place.get("fallback_image_url")),
                textOf(place.get("image_credit")),
                textOf(place.get("image_credit_url")),
                doubleOf(place.get("latitude")),
                doubleOf(place.get("longitude")),
                doubleOf(place.get("rating")),
                textOf(place.get("signature_dish")),
                textOf(place.get("meal_slot")),
                Boolean.TRUE.equals(place.get("over_budget")),
                intOf(place.get("budget_limit")),
                textOf(place.get("budget_note")),
                textOf(place.get("price_tier")),
                textOf(place.get("price_tier_label")),
                Boolean.TRUE.equals(place.get("price_adjusted")),
                Boolean.TRUE.equals(place.get("is_free")),
                Boolean.TRUE.equals(place.get("price_pending")));
    }

    private static String textOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer intOf(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Double doubleOf(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.doubleValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static void addEvent(State state, String tool, String status, String message) {
        state.events.add(new PlanDtos.ToolEvent(tool, status, message));
    }

    private void notifyEvent(State state, ProgressListener listener) {
        if (listener == null || state.events.isEmpty()) {
            return;
        }
        PlanDtos.ToolEvent last = state.events.get(state.events.size() - 1);
        listener.onEvent(last, JobPhases.phaseOf(last.tool()), JobPhases.progressOf(last.tool()));
    }

    /** plan-jobs 的阶段表，数值与原实现一致。 */
    public static final class JobPhases {
        private static final Map<String, String> PHASES = Map.of(
                "context_loader", "context",
                "live_search", "search",
                "place_recommender", "recommendations",
                "conflict_checker", "analysis",
                "budget_estimator", "analysis",
                "budget_planner", "analysis",
                "weather_forecast", "weather",
                "weather_advisor", "weather",
                "final_summary_generator", "summary");
        private static final Map<String, Integer> PROGRESS = Map.of(
                "context_loader", 8,
                "live_search", 18,
                "place_recommender", 68,
                "conflict_checker", 76,
                "budget_estimator", 80,
                "budget_planner", 84,
                "weather_forecast", 88,
                "weather_advisor", 90,
                "final_summary_generator", 96);

        private JobPhases() {
        }

        public static String phaseOf(String tool) {
            return PHASES.getOrDefault(tool, "analysis");
        }

        public static int progressOf(String tool) {
            return PROGRESS.getOrDefault(tool, 0);
        }
    }

    /** 只是为了占位避免未使用告警的 holder（预算工具是纯静态类）。 */
    private static final class BudgetToolHolder {
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static long daysBetween(Trip trip) {
        return ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()) + 1;
    }
}
