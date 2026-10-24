package com.huxizhen.travel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.agent.adjuster.Operation;
import com.huxizhen.travel.agent.tool.BudgetTool;
import com.huxizhen.travel.agent.tool.ConflictDetector;
import com.huxizhen.travel.agent.tool.DayPlanner;
import com.huxizhen.travel.config.DashScopeProperties;
import com.huxizhen.travel.dto.adjustment.AdjustmentDtos;
import com.huxizhen.travel.dto.place.PlaceCreate;
import com.huxizhen.travel.entity.ItineraryAdjustment;
import com.huxizhen.travel.entity.ItineraryItem;
import com.huxizhen.travel.entity.Place;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.integration.llm.DashScopeClient;
import com.huxizhen.travel.integration.search.WebSearchService;
import com.huxizhen.travel.integration.weather.WeatherService;
import com.huxizhen.travel.mapper.ItineraryAdjustmentMapper;
import com.huxizhen.travel.mapper.ItineraryItemMapper;
import com.huxizhen.travel.mapper.PlaceMapper;
import com.huxizhen.travel.mapper.TripMapper;
import com.huxizhen.travel.service.vector.VectorStore;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 行程调整：草案生成 + 两阶段写入 + 拒绝。
 *
 * <p>**草案生成绝不写行程表**。只有 {@link #apply} 才落库，而且"更新草案状态"与"写入行程项"
 * 在同一个事务里——半途失败必须整体回滚，否则用户会看到一个已应用的草案和没变过的行程。
 */
@Service
public class AdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration MODEL_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern BUDGET_PATTERN = Pattern.compile(
            "(?:预算|花费|费用)[^0-9]{0,12}(\\d{2,7})\\s*(?:元|块|人民币|CNY|rmb)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SCHEDULE_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{1,2}:\\d{2})\\s*[-~–—至到]\\s*(\\d{1,2}:\\d{2})");
    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile(
            "(\\d{1,2}):(\\d{2})\\s*[-~–—至到]\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern FULL_DATE_PATTERN = Pattern.compile(
            "(20\\d{2})[-年](\\d{1,2})[-月](\\d{1,2})日?");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})(?:日|号)?");
    private static final Pattern DAY_WORD_PATTERN = Pattern.compile("第\\s*([0-9一二三四五六七八九十]{1,3})\\s*天");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("[「『“\"]([^」』”\"]+)[」』”\"]");
    private static final Pattern END_TIME_PATTERN = Pattern.compile(
            "(?:晚上|晚间|到|至)\\s*(\\d{1,2})\\s*点(?:\\s*(\\d{1,2})\\s*分)?");

    private static final List<String> EMERGENCY_WORDS = List.of(
            "暴雨", "大雨", "台风", "天气不好", "下雨", "无法出行", "不能去", "去不了",
            "景点关闭", "闭园", "交通中断", "交通停运");
    private static final List<String> TIMELINE_WORDS = List.of(
            "乐园", "游乐园", "游玩", "晚饭", "晚餐", "必吃", "必喝");
    private static final List<String> REPLACE_WORDS = List.of(
            "换成", "替换", "改成", "改为", "换掉", "不要", "别去", "去掉", "删除");
    private static final List<String> SECOND_TASK_WORDS = List.of(
            "换", "替换", "删", "去掉", "取消", "增加", "加一个", "加个", "安排", "推荐", "想吃", "想去");

    private final ItineraryAdjustmentMapper adjustmentMapper;
    private final ItineraryItemMapper itemMapper;
    private final PlaceMapper placeMapper;
    private final TripMapper tripMapper;
    private final ItineraryService itineraryService;
    private final PlaceService placeService;
    private final VectorIndexService vectorIndexService;
    private final WebSearchService webSearchService;
    private final WeatherService weatherService;
    private final DayPlanner dayPlanner;
    private final DashScopeClient client;
    private final DashScopeProperties properties;

    public AdjustmentService(
            ItineraryAdjustmentMapper adjustmentMapper,
            ItineraryItemMapper itemMapper,
            PlaceMapper placeMapper,
            TripMapper tripMapper,
            ItineraryService itineraryService,
            PlaceService placeService,
            VectorIndexService vectorIndexService,
            WebSearchService webSearchService,
            WeatherService weatherService,
            DayPlanner dayPlanner,
            DashScopeClient client,
            DashScopeProperties properties) {
        this.adjustmentMapper = adjustmentMapper;
        this.itemMapper = itemMapper;
        this.placeMapper = placeMapper;
        this.tripMapper = tripMapper;
        this.itineraryService = itineraryService;
        this.placeService = placeService;
        this.vectorIndexService = vectorIndexService;
        this.webSearchService = webSearchService;
        this.weatherService = weatherService;
        this.dayPlanner = dayPlanner;
        this.client = client;
        this.properties = properties;
    }

    /** 业务错误：草案状态不对（→409）或操作不合法（→422）。 */
    public static class AdjustmentStateException extends RuntimeException {
        public AdjustmentStateException(String message) {
            super(message);
        }
    }

    public static class AdjustmentValidationException extends RuntimeException {
        public AdjustmentValidationException(String message) {
            super(message);
        }
    }

    // --------------------------------------------------------------- queries

    public ItineraryAdjustment get(Long tripId, Long adjustmentId) {
        return adjustmentMapper.selectOne(Wrappers.<ItineraryAdjustment>lambdaQuery()
                .eq(ItineraryAdjustment::getId, adjustmentId)
                .eq(ItineraryAdjustment::getTripId, tripId));
    }

    public List<ItineraryAdjustment> list(Long tripId) {
        return adjustmentMapper.selectList(Wrappers.<ItineraryAdjustment>lambdaQuery()
                .eq(ItineraryAdjustment::getTripId, tripId)
                .orderByDesc(ItineraryAdjustment::getCreatedAt)
                .orderByDesc(ItineraryAdjustment::getId));
    }

    /** 草案的对外形状：把持久化的 proposal_json 摊平成响应字段。 */
    public AdjustmentDtos.AdjustmentRead toRead(ItineraryAdjustment adjustment) {
        Map<String, Object> proposal = readProposal(adjustment);
        return new AdjustmentDtos.AdjustmentRead(
                adjustment.getId(),
                adjustment.getTripId(),
                adjustment.getInstruction(),
                adjustment.getStatus(),
                listOfMaps(proposal.get("operations")),
                str(proposal.get("summary")),
                listOfMaps(proposal.get("sources")),
                listOfMaps(proposal.get("events")),
                mapOf(proposal.get("budget")),
                listOfMaps(proposal.get("conflicts")),
                listOfMaps(proposal.get("daily_plan")),
                str(proposal.get("final_summary")),
                mapOf(proposal.get("traveler_profile")),
                mapOf(proposal.get("weather")));
    }

    // ---------------------------------------------------------------- draft

    @Transactional
    public AdjustmentDtos.AdjustmentRead createDraft(
            Trip trip, AdjustmentDtos.AdjustmentDraftRequest request) {
        Map<String, Object> profile = profileOf(trip);
        List<Operation> requested = parseOperations(request.operations());
        if (!requested.isEmpty()) {
            validateOperations(trip, requested);
        }
        List<Map<String, Object>> events = new ArrayList<>();

        String mode = request.mode();
        if ("day_plan".equals(mode)) {
            return dayPlanDraft(trip, request, profile, events);
        }
        if ("discover".equals(mode)) {
            return discoveryDraft(trip, request, profile, events);
        }
        if (mode != null && !mode.isBlank()) {
            throw new AdjustmentValidationException("不支持的调整模式。");
        }

        List<Place> places = placeService.listEntities(trip.getId());
        List<ItineraryItem> items = itineraryService.listEntities(trip.getId());
        Map<String, Object> weather = weatherService.getForecast(
                trip.getDestination(), trip.getStartDate().toString(), trip.getEndDate().toString());
        events.add(newEvent("weather_forecast",
                String.valueOf(weather.getOrDefault("status", "fallback")),
                "completed".equals(weather.get("status"))
                        ? "已查询旅行日期天气预报。"
                        : String.valueOf(weather.getOrDefault("message", "天气预报不可用。"))));

        TravelerDocuments documents = retrieveDocuments(trip, request.instruction());
        events.add(documents.event());
        events.add(contextEvent(true));

        String focused = focusedQuery(request.instruction());
        List<Map<String, Object>> webResults = List.of();
        if (request.includeWeb()) {
            WebSearchService.SearchResult search = webSearchService.searchWeb(focused, trip.getDestination());
            webResults = search.results();
            events.add(newEvent("live_search",
                    "completed".equals(search.status()) ? "completed" : "fallback", search.status()));
        } else {
            events.add(newEvent("live_search", "skipped", "用户未启用联网搜索。"));
        }

        List<Operation> operations = resolveOperations(
                trip, request, requested, places, items, webResults, weather, events,
                documents.chunks());

        Map<String, Object> preview = preview(trip, places, items, operations, profile, weather);
        events.addAll((List<Map<String, Object>>) preview.get("events"));
        Map<String, Object> proposal = proposalOf(
                operations,
                str(preview.get("summary")),
                buildSources(places, webResults),
                events,
                mapOf(preview.get("budget")),
                listOfMaps(preview.get("conflicts")),
                listOfMaps(preview.get("daily_plan")),
                str(preview.get("final_summary")),
                profile,
                weather);
        return persist(trip, request.instruction(), proposal);
    }

    /** 规则优先、模型补全、再不行用兜底规则；异常必须降级而不是 500。 */
    private List<Operation> resolveOperations(
            Trip trip,
            AdjustmentDtos.AdjustmentDraftRequest request,
            List<Operation> requested,
            List<Place> places,
            List<ItineraryItem> items,
            List<Map<String, Object>> webResults,
            Map<String, Object> weather,
            List<Map<String, Object>> events,
            List<Map<String, Object>> travelerDocuments) {
        String instruction = request.instruction();
        if (!requested.isEmpty() && request.structuredOnly()) {
            events.add(newEvent("adjustment_parser", "completed",
                    "已按你在界面上选择的 " + requested.size() + " 项改动生成草案。"));
            return requested;
        }
        if (!requested.isEmpty()) {
            List<Operation> fromModel = modelOperations(
                    trip, instruction, places, items, webResults, request.candidates(), weather, travelerDocuments);
            List<Operation> extra = fromModel.stream()
                    .filter(operation -> !conflictsWithManual(operation, requested))
                    .toList();
            events.add(newEvent("adjustment_parser", "completed",
                    "已合并你在界面上的 " + requested.size() + " 项改动和文字要求里的 " + extra.size() + " 项改动。"));
            List<Operation> merged = new ArrayList<>(requested);
            merged.addAll(extra);
            return merged;
        }

        Operation budget = budgetOperation(instruction);
        List<Operation> emergency = emergencyOperations(trip, instruction, weather, places, items);
        List<Operation> replacement = emergency.isEmpty()
                ? replacementOperations(trip, instruction, places, items, webResults)
                : List.of();
        List<Operation> explicit = new ArrayList<>();
        if (budget != null) {
            explicit.add(budget);
        }
        explicit.addAll(emergency.isEmpty() ? replacement : emergency);

        List<Operation> operations;
        if (explicit.isEmpty()) {
            // 明确的日期/时间线请求优先走本地规则，避免模型把"乐园半天"排成三餐。
            LocalDate requestedDay = requestDate(instruction, trip);
            if (isTimelineRequest(instruction) && requestedDay != null
                    && !SCHEDULE_PATTERN.matcher(instruction).find()) {
                List<Operation> timeline = timelineOperations(trip, instruction, requestedDay, places, webResults);
                operations = timeline.isEmpty()
                        ? modelOperations(trip, instruction, places, items, webResults,
                                request.candidates(), weather, travelerDocuments)
                        : timeline;
            } else {
                operations = modelOperations(trip, instruction, places, items, webResults,
                        request.candidates(), weather, travelerDocuments);
            }
        } else {
            operations = new ArrayList<>(explicit);
            if (emergency.isEmpty() && replacement.isEmpty() && asksForMoreThanBudget(instruction, places, items)) {
                // 预算只是句子里的一半，另一半（换地点/改时间）交给模型补全。
                List<Operation> extra = new ArrayList<>();
                for (Operation operation : modelOperations(trip, instruction, places, items, webResults,
                        request.candidates(), weather, travelerDocuments)) {
                    if (!conflictsWithManual(operation, explicit)) {
                        extra.add(operation);
                    }
                }
                operations.addAll(extra);
            }
        }
        String message;
        if (!emergency.isEmpty()) {
            message = "已按天气/闭园/交通中断生成室内替代时间线。";
        } else if (!explicit.isEmpty() && operations.size() > explicit.size()) {
            message = "已按明确的预算或替换要求生成草案，并用模型补全了文字里的其他改动。";
        } else if (!explicit.isEmpty()) {
            message = "已按明确的预算、删除或替换要求生成结构化草案。";
        } else {
            message = "已生成结构化行程调整草案。";
        }
        events.add(newEvent("adjustment_parser", "completed", message));
        return operations;
    }

    private List<Operation> modelOperations(
            Trip trip,
            String instruction,
            List<Place> places,
            List<ItineraryItem> items,
            List<Map<String, Object>> webResults,
            List<AdjustmentDtos.AdjustmentCandidate> candidates,
            Map<String, Object> weather,
            List<Map<String, Object>> travelerDocuments) {
        if (!client.configured()) {
            return fallbackOperations(trip, instruction, places, items);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instruction", instruction);
        payload.put("trip", Map.of(
                "id", trip.getId(),
                "destination", trip.getDestination(),
                "start_date", trip.getStartDate().toString(),
                "end_date", trip.getEndDate().toString(),
                "budget", trip.getBudget() == null ? "" : trip.getBudget()));
        payload.put("saved_places", places.stream().map(this::placeBrief).toList());
        payload.put("itinerary", items.stream().map(this::itemBrief).toList());
        payload.put("buildings_from_planning_agent", candidates == null ? List.of() : candidates);
        payload.put("web_results", webResults);
        payload.put("traveler_profile", profileOf(trip));
        payload.put("weather", weather == null ? Map.of() : weather);
        payload.put("traveler_documents", travelerDocuments == null ? List.of() : travelerDocuments);
        try {
            String content = client.complete(
                    DashScopeClient.textBody(
                            properties.planningModel(),
                            SYSTEM_PROMPT,
                            toJson(payload),
                            true,
                            null,
                            0.0),
                    MODEL_TIMEOUT);
            return parseModelOperations(content);
        } catch (RuntimeException error) {
            log.warn("model adjustment failed, falling back to rules: {}", error.toString());
            return fallbackOperations(trip, instruction, places, items);
        }
    }

    private static final String SYSTEM_PROMPT = """
            你把旅行行程调整请求转换成 JSON，只输出一个对象：{"operations": [...]}。
            每个操作包含 kind（remove_item, add_item, add_place_and_item, update_item, set_budget）、
            item_id、place_id、place、date、start_time、end_time、note、sort_order。
            尽量使用已收藏地点的 ID，绝不编造 ID。日期必须落在旅行日期范围内。
            用户只是想把某项换到另一天或改时间时，用 update_item（带 item_id 与新时间），不要用 remove_item。
            buildings_from_planning_agent 是新地点的第一来源：其中的 estimated_cost 与 quantity 是用户
            已经看到的价格，添加时照抄，不要另外编价格。以当前行程为唯一基线，保持未受影响的日期与时段。
            traveler_profile（成人/儿童人数）用于选择适龄、无障碍的活动。
            weather 与 traveler_documents 是内部依据：天气导致户外不安全时给出完整的室内替代时间线；
            旅行者上传的订单/票据（含来源文件名）优先级高于任何猜测，涉及已订酒店、机票、票价或订单号时
            必须按文档回答并说明用了哪份文档，绝不能编造一个文档里没有的预订，也不能与文档矛盾。
            sources 只作为内部上下文，绝不能出现在给用户的文字里。""";

    private List<Operation> fallbackOperations(
            Trip trip, String instruction, List<Place> places, List<ItineraryItem> items) {
        List<Operation> operations = new ArrayList<>();
        Operation budget = budgetOperation(instruction);
        if (budget != null) {
            operations.add(budget);
        }
        Matcher schedule = SCHEDULE_PATTERN.matcher(instruction);
        LocalDate requestDate = requestDate(instruction, trip);
        String matchedName = places.stream()
                .map(Place::getName)
                .filter(name -> name != null && instruction.toLowerCase().contains(name.toLowerCase()))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        Place place = matchedName == null
                ? null
                : places.stream().filter(p -> matchedName.equals(p.getName())).findFirst().orElse(null);
        if (place != null && schedule.find()) {
            operations.add(new Operation(
                    "add_item", null, place.getId(), null,
                    LocalDate.parse(schedule.group(1)),
                    parseClock(schedule.group(2)),
                    parseClock(schedule.group(3)),
                    "由行程调整 Agent 建议", 0, null));
        } else if (place != null && requestDate != null) {
            operations.add(new Operation(
                    "add_item", null, place.getId(), null,
                    requestDate,
                    LocalTime.of(14, 0),
                    requestEndTime(instruction),
                    "由行程调整 Agent 建议", 0, null));
        }
        return operations;
    }

    private List<Operation> replacementOperations(
            Trip trip,
            String instruction,
            List<Place> places,
            List<ItineraryItem> items,
            List<Map<String, Object>> webResults) {
        boolean wants = List.of("不想", "不要", "去掉", "删除", "换一个", "换个", "替换").stream()
                .anyMatch(instruction::contains);
        if (!wants) {
            return List.of();
        }
        // 时间段可以不带日期："把 14:00-16:00 的博物馆换成室内的"。
        Matcher timeRange = TIME_RANGE_PATTERN.matcher(instruction);
        if (!timeRange.find()) {
            return List.of();
        }
        LocalTime start = parseClock(timeRange.group(1) + ":" + timeRange.group(2));
        LocalTime end = parseClock(timeRange.group(3) + ":" + timeRange.group(4));
        List<String> components = quotedComponents(instruction);
        List<String> candidates = new ArrayList<>();
        for (Place place : places) {
            if (place.getName() != null && !place.getName().isBlank()) {
                candidates.add(place.getName());
            }
        }
        for (Map<String, Object> result : webResults) {
            Object title = result.get("title");
            if (title != null && !String.valueOf(title).isBlank()) {
                candidates.add(String.valueOf(title));
            }
        }
        if (trip.getDestination() != null
                && (trip.getDestination().contains("迪士尼") || trip.getDestination().contains("乐园"))) {
            candidates.addAll(List.of("加勒比海盗——沉落宝藏之战", "飞跃地平线", "创极速光轮"));
        }
        Map<Long, String> placeNames = new LinkedHashMap<>();
        for (Place place : places) {
            placeNames.put(place.getId(), place.getName());
        }

        List<Operation> operations = new ArrayList<>();
        LinkedHashSet<String> usedNames = new LinkedHashSet<>(components);
        for (ItineraryItem item : items) {
            boolean sameSlot = start.equals(item.getStartTime()) && end.equals(item.getEndTime());
            String placeName = placeNames.getOrDefault(item.getPlaceId(), "");
            boolean sameName = components.isEmpty()
                    || components.stream().anyMatch(component -> placeName.contains(component));
            if (!sameSlot || !sameName) {
                continue;
            }
            String replacementName = null;
            for (String candidate : candidates) {
                if (candidate.isBlank() || usedNames.contains(candidate)) {
                    continue;
                }
                boolean overlaps = components.stream()
                        .anyMatch(component -> candidate.contains(component) || component.contains(candidate));
                if (!overlaps) {
                    replacementName = candidate;
                    break;
                }
            }
            if (replacementName == null) {
                replacementName = trip.getDestination() + "备用推荐项目 " + (operations.size() / 2 + 1);
            }
            usedNames.add(replacementName);
            operations.add(new Operation("remove_item", item.getId(), null, null, null, null, null, null, 0, null));
            operations.add(new Operation(
                    "add_place_and_item", null, null,
                    new PlaceCreate(
                            replacementName,
                            com.huxizhen.travel.dto.place.PlaceType.ATTRACTION,
                            trip.getDestination(),
                            null, null, null, null, 2, null, null,
                            "替换为新的推荐项目，保留原时间段", null, null, null),
                    item.getDate(), start, end, "替换为新的推荐项目，保留原时间段",
                    item.getSortOrder() == null ? 0 : item.getSortOrder(), null));
        }
        return operations;
    }

    private static Operation budgetOperation(String instruction) {
        if (!List.of("预算", "花费", "费用").stream().anyMatch(instruction::contains)) {
            return null;
        }
        Matcher matcher = BUDGET_PATTERN.matcher(instruction);
        if (!matcher.find()) {
            return null;
        }
        int amount = Integer.parseInt(matcher.group(1));
        return amount <= 0 ? null : new Operation("set_budget", null, null, null, null, null, null, null, 0, amount);
    }

    private static boolean conflictsWithManual(Operation model, List<Operation> manual) {
        if ("set_budget".equals(model.kind()) && manual.stream().anyMatch(op -> "set_budget".equals(op.kind()))) {
            return true;
        }
        List<Long> touched = manual.stream()
                .filter(op -> List.of("remove_item", "update_item").contains(op.kind()))
                .map(Operation::itemId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (List.of("remove_item", "update_item").contains(model.kind()) && touched.contains(model.itemId())) {
            return true;
        }
        List<String> occupied = manual.stream()
                .filter(op -> List.of("add_item", "add_place_and_item").contains(op.kind()))
                .map(op -> op.date() + " " + op.startTime())
                .toList();
        if (List.of("add_item", "add_place_and_item").contains(model.kind())) {
            return occupied.contains(model.date() + " " + model.startTime());
        }
        return false;
    }

    // ----------------------------------------------------------- preview

    /** 把 operations 影子应用到行程副本上，算出预算、冲突与逐日预览。 */
    private Map<String, Object> preview(
            Trip trip,
            List<Place> places,
            List<ItineraryItem> items,
            List<Operation> operations,
            Map<String, Object> profile,
            Map<String, Object> weather) {
        Map<Long, Place> byId = new LinkedHashMap<>();
        places.forEach(place -> byId.put(place.getId(), place));

        List<Map<String, Object>> previewItems = new ArrayList<>();
        int sortOrder = 0;
        for (ItineraryItem item : items) {
            Place kept = byId.get(item.getPlaceId());
            Map<String, Object> entry = itemBrief(item);
            entry.put("changed", "kept");
            entry.put("place_id", item.getPlaceId());
            entry.put("date", item.getDate().toString());
            entry.put("start_time", item.getStartTime().toString());
            entry.put("end_time", item.getEndTime().toString());
            entry.put("place_name", kept != null ? kept.getName() : "行程安排");
            entry.put("type", kept != null ? kept.getType() : null);
            // 这几项决定"保留项"的预算按什么价计：没有它们就会掉到类别默认价，
            // 用户已经同意的报价（quoted_cost）和价格等级必须一起带进预览。
            entry.put("address", kept != null ? kept.getAddress() : null);
            entry.put("image_url", kept != null ? kept.getImageUrl() : null);
            entry.put("price_level", kept != null ? kept.getPriceLevel() : null);
            entry.put("quoted_cost", kept != null ? kept.getEstimatedCost() : null);
            entry.put("quantity", kept != null ? kept.getQuantity() : null);
            previewItems.add(entry);
        }
        for (int index = 0; index < operations.size(); index++) {
            Operation operation = operations.get(index);
            switch (operation.kind()) {
                case "remove_item" -> previewItems.removeIf(entry ->
                        java.util.Objects.equals(longOf(entry.get("id")), operation.itemId()));
                case "update_item" -> {
                    for (Map<String, Object> entry : previewItems) {
                        if (!java.util.Objects.equals(longOf(entry.get("id")), operation.itemId())) {
                            continue;
                        }
                        if (operation.date() != null) {
                            entry.put("date", operation.date().toString());
                        }
                        if (operation.startTime() != null) {
                            entry.put("start_time", operation.startTime().toString());
                        }
                        if (operation.endTime() != null) {
                            entry.put("end_time", operation.endTime().toString());
                        }
                        if (operation.note() != null) {
                            entry.put("note", operation.note());
                        }
                        entry.put("changed", "moved");
                        entry.put("operation_index", index);
                    }
                }
                case "add_item" -> {
                    Place place = byId.get(operation.placeId());
                    if (place != null) {
                        previewItems.add(addedEntry(place.getName(), place.getType(), place.getAddress(),
                                place.getImageUrl(), operation, index, place.getEstimatedCost(),
                                place.getQuantity(), place.getPriceLevel()));
                    }
                }
                case "add_place_and_item" -> {
                    PlaceCreate place = operation.place();
                    if (place != null) {
                        previewItems.add(addedEntry(place.name(), place.type() == null ? null : place.type().value(),
                                place.address(), place.imageUrl(), operation, index,
                                place.estimatedCost(), place.quantity(), place.priceLevel()));
                    }
                }
                default -> {
                    // set_budget 不改行程
                }
            }
            sortOrder++;
        }

        List<ConflictDetector.Conflict> conflicts = ConflictDetector.detect(previewItems);
        int days = (int) Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(
                trip.getStartDate(), trip.getEndDate()) + 1);
        Operation budgetOp = operations.stream()
                .filter(operation -> "set_budget".equals(operation.kind()))
                .findFirst()
                .orElse(null);
        Integer requestedBudget = budgetOp != null ? budgetOp.budgetAmount() : trip.getBudget();
        BudgetTool.Estimate budget = BudgetTool.estimateItineraryBudget(previewItems, days, requestedBudget, profile);

        List<Map<String, Object>> daily = dailyPreview(trip, previewItems);
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(newEvent("conflict_checker", "completed", "检测到 " + conflicts.size() + " 个时间冲突。"));
        events.add(newEvent("budget_estimator", "completed",
                "已按调整后的每日行程逐项估算：共 " + Math.max(0, budget.breakdown().size() - 1) + " 个行程项。"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", draftSummary(operations));
        result.put("daily_plan", daily);
        result.put("final_summary", finalSummary(trip, daily, budget, operations, items, places));
        result.put("budget", PlanningPipelineBudget.map(budget));
        result.put("conflicts", daily.isEmpty() ? List.of() : conflicts.stream()
                .map(conflict -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("date", conflict.date().toString());
                    map.put("first_place", conflict.firstPlace());
                    map.put("second_place", conflict.secondPlace());
                    map.put("message", conflict.message());
                    return map;
                }).toList());
        result.put("events", events);
        result.put("weather", weather == null ? Map.of() : weather);
        return result;
    }

    private Map<String, Object> addedEntry(
            String name,
            String type,
            String address,
            String imageUrl,
            Operation operation,
            int index,
            Integer quotedCost,
            Integer quantity,
            Integer priceLevel) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", null);
        entry.put("place_id", operation.placeId());
        entry.put("date", operation.date() == null ? null : operation.date().toString());
        entry.put("start_time", operation.startTime() == null ? null : operation.startTime().toString());
        entry.put("end_time", operation.endTime() == null ? null : operation.endTime().toString());
        entry.put("place_name", name);
        entry.put("image_url", imageUrl);
        entry.put("note", operation.note());
        entry.put("sort_order", operation.sortOrder());
        entry.put("type", type);
        entry.put("quoted_cost", quotedCost);
        entry.put("quantity", quantity);
        entry.put("price_level", priceLevel);
        entry.put("address", address);
        entry.put("changed", "added");
        entry.put("operation_index", index);
        return entry;
    }

    private List<Map<String, Object>> dailyPreview(Trip trip, List<Map<String, Object>> previewItems) {
        List<String> days = new ArrayList<>();
        for (LocalDate cursor = trip.getStartDate(); !cursor.isAfter(trip.getEndDate()); cursor = cursor.plusDays(1)) {
            days.add(cursor.toString());
        }
        for (Map<String, Object> item : previewItems) {
            String date = String.valueOf(item.get("date"));
            if (!days.contains(date)) {
                days.add(date);
            }
        }
        days.sort(String::compareTo);

        List<Map<String, Object>> plan = new ArrayList<>();
        for (String day : days) {
            List<Map<String, Object>> entries = new ArrayList<>();
            previewItems.stream()
                    .filter(item -> day.equals(String.valueOf(item.get("date"))))
                    .sorted(Comparator.comparing(item -> String.valueOf(item.get("start_time"))))
                    .forEach(item -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("item_id", item.get("id"));
                        entry.put("place_id", item.get("place_id"));
                        entry.put("start_time", item.get("start_time"));
                        entry.put("end_time", item.get("end_time"));
                        entry.put("place_name", item.get("place_name"));
                        entry.put("note", item.get("note"));
                        entry.put("image_url", item.get("image_url"));
                        entry.put("type", item.get("type"));
                        entry.put("changed", item.getOrDefault("changed", "kept"));
                        entries.add(entry);
                    });
            Map<String, Object> dayEntry = new LinkedHashMap<>();
            dayEntry.put("date", day);
            dayEntry.put("items", entries);
            plan.add(dayEntry);
        }
        return plan;
    }

    private String draftSummary(List<Operation> operations) {
        long removed = operations.stream().filter(op -> "remove_item".equals(op.kind())).count();
        long added = operations.stream().filter(op -> List.of("add_item", "add_place_and_item").contains(op.kind())).count();
        long moved = operations.stream().filter(op -> "update_item".equals(op.kind())).count();
        boolean budget = operations.stream().anyMatch(op -> "set_budget".equals(op.kind()));
        List<String> parts = new ArrayList<>();
        if (removed > 0) {
            parts.add("删除 " + removed + " 项");
        }
        if (added > 0) {
            parts.add("新增 " + added + " 项");
        }
        if (moved > 0) {
            parts.add("调整 " + moved + " 项的时间或日期");
        }
        if (budget) {
            parts.add("调整旅行总预算");
        }
        if (parts.isEmpty()) {
            return "没有识别出可执行的行程改动；请在下面直接勾选要删除、移动或新增的项目。";
        }
        return "草案：" + String.join("、", parts) + "。确认应用前不会修改已保存的地点或行程。";
    }

    private String finalSummary(
            Trip trip,
            List<Map<String, Object>> daily,
            BudgetTool.Estimate budget,
            List<Operation> operations,
            List<ItineraryItem> items,
            List<Place> places) {
        String destination = trip.getDestination();
        List<Operation> removed = operations.stream()
                .filter(op -> "remove_item".equals(op.kind()))
                .toList();
        List<String> added = operations.stream()
                .filter(op -> "add_place_and_item".equals(op.kind()) && op.place() != null)
                .map(op -> op.place().name())
                .toList();
        long moved = operations.stream().filter(op -> "update_item".equals(op.kind())).count();
        Operation budgetOp = operations.stream().filter(op -> "set_budget".equals(op.kind())).findFirst().orElse(null);
        int scheduled = daily.stream().mapToInt(day -> ((List<?>) day.get("items")).size()).sum();

        String money = "调整后的行程共 " + scheduled + " 项，逐项估算约 " + budget.totalEstimate() + " "
                + budget.currency();
        if (budget.tripBudget() != null && budget.remainingBudget() != null) {
            money += "，相对 " + budget.tripBudget() + " CNY 的旅行预算还剩 " + budget.remainingBudget() + " CNY";
        }
        money += "。";

        List<String> parts = new ArrayList<>();
        if (!removed.isEmpty()) {
            List<String> names = removedNames(removed, items, places);
            String label = names.isEmpty() ? "" : "（" + String.join("、", names.subList(0, Math.min(4, names.size()))) + "）";
            parts.add("删除 " + removed.size() + " 个行程项" + label);
        }
        if (!added.isEmpty()) {
            parts.add("新增 " + String.join("、", added.subList(0, Math.min(4, added.size())))
                    + (added.size() > 4 ? "等" : ""));
        }
        if (moved > 0) {
            parts.add("移动或改时间 " + moved + " 项");
        }
        if (budgetOp != null) {
            parts.add("把旅行总预算调整为 " + budgetOp.budgetAmount() + " CNY");
        }
        if (parts.isEmpty()) {
            return destination + " 的行程没有变化。可以在下面直接删除、移动或新增项目，也可以在用自然语言说明要求。";
        }
        return "本次调整：" + String.join("；", parts) + "。" + money + "确认应用后才会写入行程。";
    }

    private static List<String> removedNames(
            List<Operation> removed, List<ItineraryItem> items, List<Place> places) {
        Map<Long, Long> itemPlace = new LinkedHashMap<>();
        for (ItineraryItem item : items) {
            itemPlace.put(item.getId(), item.getPlaceId());
        }
        Map<Long, String> placeNames = new LinkedHashMap<>();
        for (Place place : places) {
            placeNames.put(place.getId(), place.getName());
        }
        List<String> names = new ArrayList<>();
        for (Operation operation : removed) {
            String name = placeNames.get(itemPlace.get(operation.itemId()));
            if (name != null && !name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    // ------------------------------------------------------------- day plan

    private AdjustmentDtos.AdjustmentRead dayPlanDraft(
            Trip trip,
            AdjustmentDtos.AdjustmentDraftRequest request,
            Map<String, Object> profile,
            List<Map<String, Object>> events) {
        LocalDate asked = requestDate(request.instruction(), trip);
        LocalDate target = request.date() != null ? request.date() : (asked != null ? asked : trip.getStartDate());
        if (target.isBefore(trip.getStartDate()) || target.isAfter(trip.getEndDate())) {
            throw new AdjustmentValidationException("这一天不在旅行日期范围内。");
        }
        List<ItineraryItem> items = itineraryService.listEntities(trip.getId());
        Map<Long, Place> byId = placeById(trip.getId());
        List<String> usedOtherDays = new ArrayList<>();
        for (ItineraryItem item : items) {
            if (!target.equals(item.getDate())) {
                Place place = byId.get(item.getPlaceId());
                if (place != null && place.getName() != null) {
                    usedOtherDays.add(place.getName());
                }
            }
        }
        int days = (int) Math.max(1, ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()) + 1);
        List<Map<String, Object>> candidates = dayPlanner.planOneDay(
                trip.getDestination(), days, usedOtherDays, profile, trip.getBudget());

        List<Operation> operations = new ArrayList<>();
        // Python 的 day_operations 是"替换这一天"：先把当天已有项目删掉，再排新的一天。
        // 只加不删会让同一天出现两份行程，应用后立刻时间冲突。
        for (ItineraryItem item : items) {
            if (target.equals(item.getDate())) {
                operations.add(new Operation(
                        "remove_item", item.getId(), null, null, null, null, null, null, 0, null));
            }
        }
        List<Map<String, Object>> attractions = byType(candidates, "attraction");
        List<Map<String, Object>> restaurants = byType(candidates, "restaurant");
        List<Map<String, Object>> breakfasts = new ArrayList<>();
        List<Map<String, Object>> mains = new ArrayList<>();
        for (Map<String, Object> place : restaurants) {
            if ("breakfast".equals(place.get("meal_slot"))) {
                breakfasts.add(place);
            } else {
                mains.add(place);
            }
        }
        if (breakfasts.isEmpty()) {
            breakfasts.addAll(restaurants);
        }
        if (mains.isEmpty()) {
            mains.addAll(restaurants);
        }
        LinkedHashSet<String> usedNames = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        int sortOrder = operations.size();
        Map<String, Object> breakfast = takeCandidate(breakfasts, usedNames);
        if (breakfast != null) {
            operations.add(addOperation(target, LocalTime.of(8, 0), LocalTime.of(9, 0), breakfast,
                    "当地早餐", sortOrder++, trip.getDestination()));
        } else {
            missing.add("早餐");
        }
        Map<String, Object> morning = takeCandidate(attractions, usedNames);
        if (morning != null) {
            operations.add(addOperation(target, LocalTime.of(9, 30), LocalTime.of(12, 0), morning,
                    "上午主要游览，预留交通与拍照时间", sortOrder++, trip.getDestination()));
        } else {
            missing.add("景点");
        }
        Map<String, Object> lunch = takeCandidate(mains, usedNames);
        if (lunch != null) {
            operations.add(addOperation(target, LocalTime.of(12, 0), LocalTime.of(13, 0), lunch,
                    "当地特色午餐", sortOrder++, trip.getDestination()));
        } else {
            missing.add("午餐");
        }
        Map<String, Object> afternoon = takeCandidate(attractions, usedNames);
        if (afternoon != null) {
            operations.add(addOperation(target, LocalTime.of(14, 0), LocalTime.of(17, 0), afternoon,
                    "下午继续游览，按现场人流灵活调整", sortOrder++, trip.getDestination()));
        }
        Map<String, Object> dinner = takeCandidate(mains, usedNames);
        if (dinner != null) {
            operations.add(addOperation(target, LocalTime.of(18, 0), LocalTime.of(19, 30), dinner,
                    "晚餐", sortOrder, trip.getDestination()));
        } else {
            missing.add("晚餐");
        }

        events.add(newEvent("day_planner", candidates.isEmpty() ? "skipped" : "completed",
                candidates.isEmpty()
                        ? target + " 暂时没有可用的推荐素材。"
                        : "为 " + target + " 整理出 " + usedNames.size() + " 个不与其他日期重复的地点。"));
        List<Map<String, Object>> shaped = previewFromOperations(target, operations);
        String summary = usedNames.isEmpty()
                ? "这一天暂时没有可安排的推荐地点。"
                : "这一天按极简方式重新安排：" + String.join("、", usedNames) + "。";
        if (!missing.isEmpty()) {
            summary += "素材里暂时没有找到" + String.join("、", missing) + "，已如实留空而不是重复别处的推荐。";
        }
        Map<String, Object> dayPlan = new LinkedHashMap<>();
        dayPlan.put("date", target.toString());
        dayPlan.put("items", shaped);
        Map<String, Object> proposal = proposalOf(
                operations, summary, List.of(), events, Map.of(), List.of(),
                List.of(dayPlan), "", profile, Map.of());
        return persist(trip, request.instruction(), proposal);
    }

    private AdjustmentDtos.AdjustmentRead discoveryDraft(
            Trip trip,
            AdjustmentDtos.AdjustmentDraftRequest request,
            Map<String, Object> profile,
            List<Map<String, Object>> events) {
        LocalDate asked = askedDay(request.instruction(), trip);
        LocalDate target = asked != null
                ? asked
                : (request.date() != null ? request.date() : trip.getStartDate());
        if (target.isBefore(trip.getStartDate()) || target.isAfter(trip.getEndDate())) {
            throw new AdjustmentValidationException("这一天不在旅行日期范围内。");
        }
        List<ItineraryItem> items = itineraryService.listEntities(trip.getId());
        Map<Long, Place> byId = placeById(trip.getId());
        List<String> used = new ArrayList<>();
        for (Place place : byId.values()) {
            if (place.getName() != null) {
                used.add(place.getName());
            }
        }
        for (ItineraryItem item : items) {
            Place place = byId.get(item.getPlaceId());
            if (place != null && place.getName() != null) {
                used.add(place.getName());
            }
        }
        int days = (int) Math.max(1, ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()) + 1);
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (request.candidates() != null) {
            for (AdjustmentDtos.AdjustmentCandidate candidate : request.candidates()) {
                if (candidate.name() == null || candidate.name().isBlank()) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", candidate.name());
                entry.put("type", candidate.category() == null ? "attraction" : candidate.category());
                entry.put("address", trip.getDestination());
                entry.put("reason", candidate.reason());
                entry.put("estimated_cost", candidate.estimatedCost());
                entry.put("unit_price", candidate.unitPrice());
                entry.put("quantity", candidate.quantity());
                entry.put("price_level", candidate.priceLevel());
                entry.put("image_url", candidate.imageUrl());
                entry.put("signature_dish", candidate.signatureDish());
                entry.put("rating", candidate.rating());
                candidates.add(entry);
            }
        }
        // 客户端没带候选（例如还没生成过规划）时，这里自己联网找——Python 的 discover 就是这样的。
        List<Map<String, Object>> discovered = dayPlanner.discover(
                trip.getDestination(), request.instruction(), used, days, profile, trip.getBudget());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> candidate : concat(candidates, discovered)) {
            String name = String.valueOf(candidate.getOrDefault("name", "")).strip();
            String category = String.valueOf(candidate.getOrDefault("type", "attraction"));
            if (name.isEmpty() || !("attraction".equals(category) || "restaurant".equals(category))) {
                continue;
            }
            if (!seen.add(name.toLowerCase()) || used.contains(name)) {
                continue;
            }
            merged.add(candidate);
            if (merged.size() >= 5) {
                break;
            }
        }

        List<Operation> operations = new ArrayList<>();
        boolean replace = REPLACE_WORDS.stream().anyMatch(request.instruction()::contains);
        if (replace) {
            for (ItineraryItem item : items) {
                Place place = byId.get(item.getPlaceId());
                String type = place == null ? null : place.getType();
                if (target.equals(item.getDate())
                        && ("attraction".equals(type) || "restaurant".equals(type))) {
                    operations.add(new Operation(
                            "remove_item", item.getId(), null, null, null, null, null, null, 0, null));
                }
            }
        }
        LocalTime[] attractionSlots = {
            LocalTime.of(9, 30), LocalTime.of(14, 0), LocalTime.of(17, 0)
        };
        LocalTime[] restaurantSlots = {LocalTime.of(12, 0), LocalTime.of(18, 0)};
        int attractionIndex = 0;
        int restaurantIndex = 0;
        int sortOrder = operations.size();
        for (Map<String, Object> candidate : merged) {
            String category = String.valueOf(candidate.get("type"));
            LocalTime start;
            LocalTime end;
            String note;
            if ("restaurant".equals(category)) {
                if (restaurantIndex >= restaurantSlots.length) {
                    break;
                }
                start = restaurantSlots[restaurantIndex];
                end = start.plusHours(1);
                note = "来自规划建议的新餐厅";
                restaurantIndex++;
            } else {
                if (attractionIndex >= attractionSlots.length) {
                    break;
                }
                start = attractionSlots[attractionIndex];
                end = start.plusHours(2);
                note = "来自规划建议的新地点";
                attractionIndex++;
            }
            operations.add(addOperation(target, start, end, candidate, note, sortOrder++, trip.getDestination()));
        }
        events.add(newEvent("place_discovery", operations.isEmpty() ? "skipped" : "completed",
                operations.isEmpty()
                        ? "没有找到符合「" + request.instruction().strip().substring(
                                0, Math.min(40, request.instruction().strip().length())) + "」的新地点。"
                        : "已找到 " + merged.size() + " 个符合要求且行程里还没有的地点，建议放在 " + target + "。"));
        List<Map<String, Object>> shaped = previewFromOperations(target, operations);
        Map<String, Object> dayPlan = new LinkedHashMap<>();
        dayPlan.put("date", target.toString());
        dayPlan.put("items", shaped);
        Map<String, Object> proposal = proposalOf(
                operations,
                operations.isEmpty() ? "没有找到符合要求的新地点。" : "已为你挑选 " + merged.size() + " 个新地点，勾选后加入行程。",
                List.of(), events, Map.of(), List.of(), List.of(dayPlan), "", profile, Map.of());
        return persist(trip, request.instruction(), proposal);
    }

    private Operation addOperation(
            LocalDate target,
            LocalTime start,
            LocalTime end,
            Map<String, Object> candidate,
            String note,
            int sortOrder,
            String destination) {
        return new Operation(
                "add_place_and_item", null, null, placePayload(candidate, destination), target, start, end,
                note, sortOrder, null);
    }

    @SuppressWarnings("unchecked")
    private PlaceCreate placePayload(Map<String, Object> candidate, String destination) {
        String type = String.valueOf(candidate.getOrDefault("type", "attraction"));
        com.huxizhen.travel.dto.place.PlaceType placeType;
        try {
            placeType = com.huxizhen.travel.dto.place.PlaceType.fromDatabase(type);
        } catch (RuntimeException error) {
            placeType = com.huxizhen.travel.dto.place.PlaceType.ATTRACTION;
        }
        if (placeType == null) {
            placeType = com.huxizhen.travel.dto.place.PlaceType.ATTRACTION;
        }
        String address = candidate.get("address") == null
                ? null
                : String.valueOf(candidate.get("address"));
        if (address == null || address.isBlank()) {
            address = destination;
        }
        return new PlaceCreate(
                String.valueOf(candidate.getOrDefault("name", "")),
                placeType,
                address,
                doubleOrNull(candidate.get("latitude")),
                doubleOrNull(candidate.get("longitude")),
                candidate.get("image_url") == null ? null : String.valueOf(candidate.get("image_url")),
                doubleOrNull(candidate.get("rating")),
                intOrNull(candidate.get("price_level")),
                null,
                null,
                candidate.get("reason") == null ? null : String.valueOf(candidate.get("reason")),
                intOrNull(candidate.get("estimated_cost")),
                intOrNull(candidate.get("quantity")),
                null);
    }

    private static List<Map<String, Object>> previewFromOperations(
            LocalDate target, List<Operation> operations) {
        List<Map<String, Object>> shaped = new ArrayList<>();
        for (Operation operation : operations) {
            if (!"add_place_and_item".equals(operation.kind()) || operation.place() == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("item_id", null);
            entry.put("place_id", null);
            entry.put("start_time", operation.startTime() == null ? null : operation.startTime().toString());
            entry.put("end_time", operation.endTime() == null ? null : operation.endTime().toString());
            entry.put("place_name", operation.place().name());
            entry.put("note", operation.note());
            entry.put("image_url", operation.place().imageUrl());
            entry.put("type", operation.place().type() == null ? null : operation.place().type().value());
            entry.put("changed", "added");
            shaped.add(entry);
        }
        return shaped;
    }

    private Map<Long, Place> placeById(Long tripId) {
        Map<Long, Place> byId = new LinkedHashMap<>();
        for (Place place : placeService.listEntities(tripId)) {
            byId.put(place.getId(), place);
        }
        return byId;
    }

    private static List<Map<String, Object>> byType(List<Map<String, Object>> places, String type) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> place : places) {
            if (type.equals(String.valueOf(place.get("type")))) {
                filtered.add(place);
            }
        }
        return filtered;
    }

    private static Map<String, Object> takeCandidate(
            List<Map<String, Object>> pool, LinkedHashSet<String> used) {
        for (Map<String, Object> candidate : pool) {
            String name = String.valueOf(candidate.getOrDefault("name", "")).strip();
            if (name.isEmpty() || used.contains(name)) {
                continue;
            }
            used.add(name);
            return candidate;
        }
        return null;
    }

    @SafeVarargs
    private static List<Map<String, Object>> concat(
            List<Map<String, Object>>... lists) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (List<Map<String, Object>> list : lists) {
            if (list != null) {
                merged.addAll(list);
            }
        }
        return merged;
    }

    // -------------------------------------------------------- planner helpers

    private static LocalTime parseClock(String value) {
        String text = value == null ? "" : value.strip();
        String[] parts = text.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return LocalTime.of(Math.floorMod(hour, 24), Math.min(59, Math.max(0, minute)));
    }

    /** "晚上8点" / "到21:30" 之类的结束时间；没说就按下午/晚上 20:00，否则 18:00。 */
    private static LocalTime requestEndTime(String instruction) {
        Matcher matcher = END_TIME_PATTERN.matcher(instruction);
        if (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            if (hour < 12 && instruction.contains("晚上")) {
                hour += 12;
            }
            return LocalTime.of(hour % 24, Math.min(59, minute));
        }
        return instruction.contains("下午") || instruction.contains("晚上")
                ? LocalTime.of(20, 0)
                : LocalTime.of(18, 0);
    }

    /** 自然语言日期：2026-12-03 / 2026年12月3日 / 12月3日 / 第3天。 */
    static LocalDate requestDate(String instruction, Trip trip) {
        if (instruction == null || instruction.isBlank()) {
            return null;
        }
        Matcher full = FULL_DATE_PATTERN.matcher(instruction);
        if (full.find()) {
            try {
                return LocalDate.of(
                        Integer.parseInt(full.group(1)),
                        Integer.parseInt(full.group(2)),
                        Integer.parseInt(full.group(3)));
            } catch (RuntimeException error) {
                return null;
            }
        }
        Matcher partial = MONTH_DAY_PATTERN.matcher(instruction);
        if (partial.find()) {
            try {
                return LocalDate.of(
                        trip.getStartDate().getYear(),
                        Integer.parseInt(partial.group(1)),
                        Integer.parseInt(partial.group(2)));
            } catch (RuntimeException error) {
                return null;
            }
        }
        return askedDay(instruction, trip);
    }

    /** 只认"第 N 天"，用于 discover 的目标日。 */
    private static LocalDate askedDay(String instruction, Trip trip) {
        if (instruction == null) {
            return null;
        }
        Matcher matcher = DAY_WORD_PATTERN.matcher(instruction);
        if (!matcher.find()) {
            return null;
        }
        int offset = dayWordOffset(matcher.group(1));
        if (offset < 1) {
            return null;
        }
        LocalDate day = trip.getStartDate().plusDays(offset - 1L);
        return day.isBefore(trip.getStartDate()) || day.isAfter(trip.getEndDate()) ? null : day;
    }

    private static int dayWordOffset(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        if (token.matches("\\d+")) {
            return Integer.parseInt(token);
        }
        if ("十".equals(token)) {
            return 10;
        }
        if (token.startsWith("十")) {
            return 10 + digit(token.substring(1));
        }
        if (token.endsWith("十")) {
            return digit(token.substring(0, 1)) * 10;
        }
        return digit(token);
    }

    private static int digit(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        String digits = "一二三四五六七八九";
        int index = digits.indexOf(token.charAt(0));
        return index < 0 ? 0 : index + 1;
    }

    private static boolean isTimelineRequest(String instruction) {
        return TIMELINE_WORDS.stream().anyMatch(instruction::contains);
    }

    /** 句子里除了预算还有第二件事（换地点/改时间/新增）时，需要再问一次模型。 */
    private boolean asksForMoreThanBudget(
            String instruction, List<Place> places, List<ItineraryItem> items) {
        if (SCHEDULE_PATTERN.matcher(instruction).find()) {
            return true;
        }
        if (SECOND_TASK_WORDS.stream().anyMatch(instruction::contains)) {
            return true;
        }
        for (Place place : places) {
            String name = place.getName();
            if (name != null && name.length() >= 2 && instruction.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> quotedComponents(String instruction) {
        List<String> components = new ArrayList<>();
        Matcher matcher = QUOTED_PATTERN.matcher(instruction);
        while (matcher.find()) {
            for (String part : matcher.group(1).split("[+＋、，,/]")) {
                String value = part.strip().replaceAll("^[\\s+＋、，,/]+|[\\s+＋、，,/]+$", "");
                if (!value.isEmpty()) {
                    components.add(value);
                }
            }
        }
        return components;
    }

    /**
     * 主题乐园类的半天/晚间请求：从 14:00 起按 30 分钟给出可勾选的时间线，
     * 18:00-19:00 固定为晚餐，19:00 之后为夜间项目。与 Python 的 _timeline_operations 对应。
     */
    private List<Operation> timelineOperations(
            Trip trip,
            String instruction,
            LocalDate day,
            List<Place> places,
            List<Map<String, Object>> webResults) {
        LocalTime end = requestEndTime(instruction);
        if (day == null || day.isBefore(trip.getStartDate()) || day.isAfter(trip.getEndDate())) {
            return List.of();
        }
        int endMinutes = end.getHour() * 60 + end.getMinute();
        List<Operation> operations = new ArrayList<>();
        int index = 0;
        for (int minutes = 14 * 60; minutes < endMinutes && index < 12; minutes += 30, index++) {
            int finishMinutes = Math.min(minutes + 30, endMinutes);
            LocalTime start = LocalTime.of(minutes / 60, minutes % 60);
            LocalTime finish = LocalTime.of(finishMinutes / 60, finishMinutes % 60);
            String name;
            String type;
            String note;
            if (minutes >= 18 * 60 && minutes < 19 * 60) {
                name = "乐园内晚餐与必吃必喝";
                type = "restaurant";
                note = "安排用餐并预留点餐、取餐和休息时间";
            } else if (minutes >= 19 * 60) {
                name = "乐园夜间项目与离园前安排";
                type = "attraction";
                note = "晚饭后继续游玩，按现场排队情况选择项目";
            } else {
                // 联网结果只有一段"检索摘要"，拿它当项目名会把整段时间线都叫成摘要；
                // 这里的名字只是可编辑的占位，用户勾选后可改。
                name = places.isEmpty()
                        ? trip.getDestination() + "乐园推荐项目 " + (index + 1)
                        : places.get(index % places.size()).getName();
                type = "attraction";
                note = "根据联网资料筛选，建议现场确认排队时间";
            }
            com.huxizhen.travel.dto.place.PlaceType placeType =
                    "restaurant".equals(type)
                            ? com.huxizhen.travel.dto.place.PlaceType.RESTAURANT
                            : com.huxizhen.travel.dto.place.PlaceType.ATTRACTION;
            operations.add(new Operation(
                    "add_place_and_item", null, null,
                    new PlaceCreate(name, placeType, trip.getDestination(), null, null, null, null, 2,
                            null, null, note, null, null, null),
                    day, start, finish, note, index, null));
        }
        return operations;
    }

    /**
     * 暴雨/台风/闭园/交通中断：把受影响那天换成"回酒店 + 室内活动 + 外卖晚餐 + 整理行程"
     * 的完整时间线，而不是只在文案里提醒。
     */
    private List<Operation> emergencyOperations(
            Trip trip,
            String instruction,
            Map<String, Object> weather,
            List<Place> places,
            List<ItineraryItem> items) {
        if (EMERGENCY_WORDS.stream().noneMatch(instruction::contains)) {
            return List.of();
        }
        List<LocalDate> affected = new ArrayList<>();
        LocalDate requested = requestDate(instruction, trip);
        if (requested != null
                && !requested.isBefore(trip.getStartDate())
                && !requested.isAfter(trip.getEndDate())) {
            affected.add(requested);
        } else if (weather != null && weather.get("days") instanceof List<?> days) {
            for (Object value : days) {
                if (!(value instanceof Map<?, ?> day) || !Boolean.TRUE.equals(day.get("indoor_priority"))) {
                    continue;
                }
                if (day.get("date") == null) {
                    continue;
                }
                try {
                    affected.add(LocalDate.parse(String.valueOf(day.get("date"))));
                } catch (RuntimeException ignored) {
                    // 单日解析失败不影响其它天
                }
            }
        }
        if (affected.isEmpty()) {
            affected.add(trip.getStartDate());
        }
        List<Operation> operations = new ArrayList<>();
        for (ItineraryItem item : items) {
            if (item.getDate() != null && affected.contains(item.getDate())) {
                operations.add(new Operation(
                        "remove_item", item.getId(), null, null, null, null, null, null, 0, null));
            }
        }
        Place hotel = places.stream().filter(place -> "hotel".equals(place.getType())).findFirst().orElse(null);
        String hotelName = hotel == null ? "酒店" : hotel.getName();
        String hotelAddress = hotel == null || hotel.getAddress() == null
                ? trip.getDestination()
                : hotel.getAddress();
        List<String[]> fallbacks = List.<String[]>of(
                new String[] {"返回酒店交通", "station", "先离开受影响区域，优先选择地铁/出租车并预留缓冲", "1"},
                new String[] {hotelName + "室内休息与亲子活动", "hotel",
                    "在酒店内休息、桌游或亲子小游戏，适合成人和儿童", "1"},
                new String[] {"酒店外卖晚餐", "restaurant", "根据实时营业状态选择外卖，优先配送到酒店", "2"},
                new String[] {"酒店内电影、桌游与旅行整理", "hotel", "安排轻量室内活动并整理第二天行程", "1"});
        for (LocalDate day : affected) {
            int minutes = 16 * 60;
            int index = 0;
            for (String[] fallback : fallbacks) {
                LocalTime start = LocalTime.of(minutes / 60, minutes % 60);
                LocalTime end = LocalTime.of((minutes + 30) / 60, (minutes + 30) % 60);
                com.huxizhen.travel.dto.place.PlaceType type = switch (fallback[1]) {
                    case "hotel" -> com.huxizhen.travel.dto.place.PlaceType.HOTEL;
                    case "restaurant" -> com.huxizhen.travel.dto.place.PlaceType.RESTAURANT;
                    case "station" -> com.huxizhen.travel.dto.place.PlaceType.STATION;
                    default -> com.huxizhen.travel.dto.place.PlaceType.ATTRACTION;
                };
                operations.add(new Operation(
                        "add_place_and_item", null, null,
                        new PlaceCreate(fallback[0], type, hotelAddress, null, null, null, null,
                                Integer.parseInt(fallback[3]), null, null, fallback[2], null, null, null),
                        day, start, end, fallback[2], index++, null));
                minutes += 30;
            }
        }
        return operations;
    }

    private record TravelerDocuments(List<Map<String, Object>> chunks, Map<String, Object> event) {
    }

    /** 检索旅客自己上传的订单/票据；失败只记录事件，不影响草案生成。 */
    private TravelerDocuments retrieveDocuments(Trip trip, String query) {
        VectorIndexService.SearchOutcome outcome = vectorIndexService.search(trip.getId(), query, 5);
        if ("ok".equals(outcome.status())) {
            List<Map<String, Object>> chunks = vectorIndexService.documentContext(outcome.chunks());
            return new TravelerDocuments(chunks, newEvent("document_search", "completed",
                    "已从上传资料中检索到 " + chunks.size() + " 段可用依据。"));
        }
        String status = List.of("empty", "skipped").contains(outcome.status()) ? "skipped" : "fallback";
        return new TravelerDocuments(List.of(), newEvent("document_search", status,
                outcome.reason() == null ? "资料检索未完成，本次未使用上传资料。" : outcome.reason()));
    }

    /** 调整草案的来源列表：收藏地点 + 联网结果（与 Python 的 sources 同形）。 */
    private static List<Map<String, Object>> buildSources(
            List<Place> places, List<Map<String, Object>> webResults) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Place place : places) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("source_type", "saved_place");
            source.put("title", place.getName());
            source.put("url", place.getSourceUrl());
            source.put("snippet", place.getAddress() == null ? "" : place.getAddress());
            source.put("score", null);
            source.put("image_url", place.getImageUrl());
            sources.add(source);
        }
        for (Map<String, Object> result : webResults) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("source_type", result.get("source_type"));
            source.put("title", result.get("title"));
            source.put("url", result.get("url"));
            source.put("snippet", result.get("snippet"));
            source.put("score", result.get("score"));
            source.put("image_url", result.get("image_url"));
            sources.add(source);
        }
        return sources;
    }

    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            if (value instanceof Number number) {
                return (int) Math.round(number.doubleValue());
            }
            String text = String.valueOf(value).replaceAll("[^0-9.\\-]", "");
            return text.isEmpty() ? null : (int) Math.round(Double.parseDouble(text));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Double doubleOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number number
                    ? number.doubleValue()
                    : Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException error) {
            return null;
        }
    }

    // --------------------------------------------------------------- apply

    @Transactional
    public List<ItineraryItem> apply(
            Trip trip,
            ItineraryAdjustment adjustment,
            List<Integer> operationIndexes,
            List<Map<String, Object>> editedOperations) {
        if (!"pending".equals(adjustment.getStatus())) {
            throw new AdjustmentStateException("只有待确认的草案才能应用。");
        }
        Map<String, Object> proposal = readProposal(adjustment);
        List<Operation> operations;
        if (editedOperations != null && !editedOperations.isEmpty()) {
            operations = parseOperations(editedOperations);
        } else {
            operations = selectOperations(listOfMaps(proposal.get("operations")), operationIndexes);
        }
        if (operations.size() > 48) {
            throw new AdjustmentValidationException("一次调整最多包含 48 个操作。");
        }
        validateOperations(trip, operations);
        for (Operation operation : operations) {
            applyOperation(trip, operation);
        }
        adjustment.setStatus("applied");
        adjustment.setAppliedAt(java.time.LocalDateTime.now(ZoneOffset.UTC));
        adjustmentMapper.updateById(adjustment);
        // 与原实现一致：返回应用后整趟行程，而不是只有新增的那些。
        return itineraryService.listEntities(trip.getId());
    }

    @Transactional
    public ItineraryAdjustment reject(ItineraryAdjustment adjustment) {
        if (!"pending".equals(adjustment.getStatus())) {
            throw new AdjustmentStateException("只有待确认的草案才能拒绝。");
        }
        adjustment.setStatus("rejected");
        adjustmentMapper.updateById(adjustment);
        return adjustmentMapper.selectById(adjustment.getId());
    }

    private void applyOperation(Trip trip, Operation operation) {
        switch (operation.kind()) {
            case "set_budget" -> {
                trip.setBudget(operation.budgetAmount());
                tripMapper.updateById(trip);
            }
            case "remove_item" -> itemMapper.deleteById(operation.itemId());
            case "update_item" -> {
                ItineraryItem item = itemMapper.selectById(operation.itemId());
                if (item == null) {
                    throw new AdjustmentValidationException("这个行程项已经不存在了。");
                }
                if (operation.date() != null) {
                    item.setDate(operation.date());
                }
                if (operation.startTime() != null) {
                    item.setStartTime(operation.startTime());
                }
                if (operation.endTime() != null) {
                    item.setEndTime(operation.endTime());
                }
                if (operation.note() != null) {
                    item.setNote(operation.note());
                }
                if (operation.sortOrder() != 0) {
                    item.setSortOrder(operation.sortOrder());
                }
                itemMapper.updateById(item);
            }
            default -> {
                Long placeId = operation.placeId();
                if ("add_place_and_item".equals(operation.kind())) {
                    PlaceCreate place = operation.place();
                    if (place == null) {
                        throw new AdjustmentValidationException("需要提供新地点。");
                    }
                    Place entity = new Place();
                    entity.setTripId(trip.getId());
                    entity.setName(place.name());
                    entity.setType(place.type().value());
                    entity.setAddress(place.address());
                    entity.setLatitude(place.latitude());
                    entity.setLongitude(place.longitude());
                    entity.setImageUrl(place.imageUrl());
                    entity.setRating(place.rating());
                    entity.setPriceLevel(place.priceLevel());
                    entity.setOpeningHours(place.openingHours());
                    entity.setSourceUrl(place.sourceUrl());
                    entity.setNote(place.note());
                    entity.setEstimatedCost(place.estimatedCost());
                    entity.setQuantity(place.quantity());
                    entity.setPreferredDate(place.preferredDate());
                    placeMapper.insert(entity);
                    placeId = entity.getId();
                }
                if (placeId == null) {
                    throw new AdjustmentValidationException("行程操作缺少必要信息。");
                }
                ItineraryItem item = new ItineraryItem();
                item.setTripId(trip.getId());
                item.setPlaceId(placeId);
                item.setDate(operation.date());
                item.setStartTime(operation.startTime());
                item.setEndTime(operation.endTime());
                item.setNote(operation.note());
                item.setSortOrder(operation.sortOrder());
                itemMapper.insert(item);
            }
        }
    }

    /** 校验每条操作能否被这趟旅行执行。 */
    public void validateOperations(Trip trip, List<Operation> operations) {
        for (Operation operation : operations) {
            if ("remove_item".equals(operation.kind())) {
                if (operation.itemId() == null || itineraryService.get(trip.getId(), operation.itemId()) == null) {
                    throw new AdjustmentValidationException("这个行程项不属于当前旅行。");
                }
                continue;
            }
            if ("set_budget".equals(operation.kind())) {
                if (operation.budgetAmount() == null || operation.budgetAmount() < 0) {
                    throw new AdjustmentValidationException("预算金额不合法。");
                }
                continue;
            }
            if (operation.date() == null || operation.startTime() == null || operation.endTime() == null) {
                throw new AdjustmentValidationException("行程需要日期和起止时间。");
            }
            if (operation.date().isBefore(trip.getStartDate()) || operation.date().isAfter(trip.getEndDate())) {
                throw new AdjustmentValidationException("行程日期不在旅行日期范围内。");
            }
            if (operation.endTime().isBefore(operation.startTime())) {
                throw new AdjustmentValidationException("行程结束时间不合法。");
            }
            if ("add_item".equals(operation.kind())) {
                if (operation.placeId() == null || placeService.get(trip.getId(), operation.placeId()) == null) {
                    throw new AdjustmentValidationException("这个地点不属于当前旅行。");
                }
            } else if ("add_place_and_item".equals(operation.kind())) {
                if (operation.place() == null) {
                    throw new AdjustmentValidationException("需要提供新地点。");
                }
            } else if ("update_item".equals(operation.kind())) {
                if (operation.itemId() == null || itineraryService.get(trip.getId(), operation.itemId()) == null) {
                    throw new AdjustmentValidationException("这个行程项不属于当前旅行。");
                }
            }
        }
    }

    // -------------------------------------------------------------- helpers

    private List<Operation> selectOperations(List<Map<String, Object>> raw, List<Integer> indexes) {
        List<Operation> operations = new ArrayList<>();
        for (Map<String, Object> entry : raw) {
            try {
                operations.add(Operation.fromMap(entry));
            } catch (RuntimeException error) {
                throw new AdjustmentValidationException("Adjustment operations are invalid.");
            }
        }
        if (indexes == null) {
            return operations;
        }
        if (indexes.stream().anyMatch(index -> index < 0 || index >= operations.size())) {
            throw new AdjustmentValidationException("选中的操作序号不合法。");
        }
        java.util.Set<Integer> selected = new java.util.TreeSet<>(indexes);
        // 替换是"相邻的删 + 加"一对：只勾一条也要成对执行，否则会被删掉却没有替代品。
        for (int index = 0; index < operations.size() - 1; index++) {
            Operation remove = operations.get(index);
            Operation add = operations.get(index + 1);
            boolean pair = "remove_item".equals(remove.kind())
                    && "add_place_and_item".equals(add.kind())
                    && add.note() != null
                    && (add.note().contains("替换") || add.note().toLowerCase().contains("replace"));
            if (pair && (selected.contains(index) || selected.contains(index + 1))) {
                selected.add(index);
                selected.add(index + 1);
            }
        }
        List<Operation> picked = new ArrayList<>();
        for (Integer index : selected) {
            picked.add(operations.get(index));
        }
        return picked;
    }

    /** 解析模型返回的 JSON：既接受 {@code {"operations": [...]}}，也接受裸数组。 */
    private List<Operation> parseModelOperations(String content) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = MAPPER.readTree(content);
            com.fasterxml.jackson.databind.JsonNode array = root.isArray() ? root : root.path("operations");
            List<Map<String, Object>> raw = new ArrayList<>();
            if (array.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : array) {
                    if (node.isObject()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = MAPPER.convertValue(node, Map.class);
                        raw.add(entry);
                    }
                }
            }
            if (raw.isEmpty()) {
                throw new AdjustmentValidationException("模型返回里没有操作列表。");
            }
            return parseOperations(raw);
        } catch (AdjustmentValidationException error) {
            throw error;
        } catch (Exception error) {
            throw new AdjustmentValidationException("模型返回里没有操作列表。");
        }
    }

    private List<Operation> parseOperations(List<Map<String, Object>> raw) {        List<Operation> operations = new ArrayList<>();
        if (raw == null) {
            return operations;
        }
        for (Map<String, Object> entry : raw) {
            try {
                operations.add(Operation.fromMap(entry));
            } catch (RuntimeException error) {
                throw new AdjustmentValidationException(
                        error.getMessage() == null ? "Adjustment operations are invalid." : error.getMessage());
            }
        }
        return operations;
    }

    private AdjustmentDtos.AdjustmentRead persist(
            Trip trip, String instruction, Map<String, Object> proposal) {
        ItineraryAdjustment adjustment = new ItineraryAdjustment();
        adjustment.setTripId(trip.getId());
        adjustment.setInstruction(instruction);
        adjustment.setProposalJson(toJson(proposal));
        adjustment.setStatus("pending");
        adjustmentMapper.insert(adjustment);
        return toRead(adjustmentMapper.selectById(adjustment.getId()));
    }

    private Map<String, Object> proposalOf(
            List<Operation> operations,
            String summary,
            List<Map<String, Object>> sources,
            List<Map<String, Object>> events,
            Map<String, Object> budget,
            List<Map<String, Object>> conflicts,
            List<Map<String, Object>> dailyPlan,
            String finalSummary,
            Map<String, Object> profile,
            Map<String, Object> weather) {
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("operations", operations.stream().map(Operation::toMap).toList());
        proposal.put("summary", summary);
        proposal.put("sources", sources);
        proposal.put("events", events);
        proposal.put("budget", budget);
        proposal.put("conflicts", conflicts);
        proposal.put("daily_plan", dailyPlan);
        proposal.put("final_summary", finalSummary);
        proposal.put("traveler_profile", profile);
        proposal.put("weather", weather);
        return proposal;
    }

    private List<Map<String, Object>> documentEvent(Trip trip, String query) {
        VectorIndexService.SearchOutcome outcome = vectorIndexService.search(trip.getId(), query, 5);
        if ("ok".equals(outcome.status())) {
            return List.of(newEvent("document_search", "completed",
                    "已从上传资料中检索到 " + outcome.chunks().size() + " 段可用依据。"));
        }
        return List.of(newEvent("document_search",
                List.of("empty", "skipped").contains(outcome.status()) ? "skipped" : "fallback",
                outcome.reason() == null ? "资料检索未完成，本次未使用上传资料。" : outcome.reason()));
    }

    private static Map<String, Object> contextEvent(boolean withDocuments) {
        return newEvent("context_loader", "completed",
                withDocuments ? "已加载旅行项目、收藏地点和当前行程。" : "已加载旅行项目、收藏地点和当前行程。");
    }

    private static Map<String, Object> newEvent(String tool, String status, String message) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tool", tool);
        event.put("status", status);
        event.put("message", message);
        return event;
    }

    private static String focusedQuery(String instruction) {
        List<String> terms = new ArrayList<>();
        if (List.of("乐园", "游乐园", "项目", "游玩").stream().anyMatch(instruction::contains)) {
            terms.add("乐园项目");
        }
        if (List.of("晚饭", "晚餐", "吃饭", "必吃").stream().anyMatch(instruction::contains)) {
            terms.add("晚餐");
        }
        if (List.of("必吃", "必喝", "美食", "饮品").stream().anyMatch(instruction::contains)) {
            terms.add("必吃必喝");
        }
        return terms.isEmpty()
                ? instruction.substring(0, Math.min(120, instruction.length()))
                : String.join(" ", new java.util.LinkedHashSet<>(terms));
    }

    private Map<String, Object> profileOf(Trip trip) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("adult_male_count", trip.getAdultMaleCount());
        profile.put("adult_female_count", trip.getAdultFemaleCount());
        profile.put("child_male_count", trip.getChildMaleCount());
        profile.put("child_female_count", trip.getChildFemaleCount());
        int total = value(trip.getAdultMaleCount()) + value(trip.getAdultFemaleCount())
                + value(trip.getChildMaleCount()) + value(trip.getChildFemaleCount());
        profile.put("total_people", total);
        return profile;
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private Map<String, Object> placeBrief(Place place) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", place.getId());
        map.put("name", place.getName());
        map.put("type", place.getType());
        map.put("address", place.getAddress());
        map.put("image_url", place.getImageUrl());
        map.put("price_level", place.getPriceLevel());
        map.put("quoted_cost", place.getEstimatedCost());
        map.put("quantity", place.getQuantity());
        map.put("rating", place.getRating());
        map.put("note", place.getNote());
        return map;
    }

    private Map<String, Object> itemBrief(ItineraryItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("place_id", item.getPlaceId());
        map.put("date", item.getDate().toString());
        map.put("start_time", item.getStartTime().toString());
        map.put("end_time", item.getEndTime().toString());
        map.put("note", item.getNote());
        map.put("sort_order", item.getSortOrder());
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readProposal(ItineraryAdjustment adjustment) {
        try {
            return MAPPER.readValue(adjustment.getProposalJson(), Map.class);
        } catch (Exception error) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("proposal could not be serialised", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 把预算结果映射成响应用的 Map（与规划响应同形）。 */
    private static final class PlanningPipelineBudget {
        static Map<String, Object> map(BudgetTool.Estimate estimate) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("total_estimate", estimate.totalEstimate());
            map.put("trip_budget", estimate.tripBudget());
            map.put("remaining_budget", estimate.remainingBudget());
            map.put("currency", estimate.currency());
            map.put("note", estimate.note());
            map.put("breakdown", estimate.breakdown());
            map.put("daily_estimate", estimate.dailyEstimate());
            map.put("itemized_summary", estimate.itemizedSummary());
            map.put("excluded_allowances", estimate.excludedAllowances());
            return map;
        }
    }
}
