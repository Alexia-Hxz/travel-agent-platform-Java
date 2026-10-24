package com.huxizhen.travel.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.config.DashScopeProperties;
import com.huxizhen.travel.integration.llm.DashScopeClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 最终旅行总结：优先让模型写，失败就用规则拼一段。
 *
 * <p>规则版不是"占位文案"：它把预算、冲突、天气建议这些**已经算好的事实**写成人话，
 * 所以模型不可用时用户仍然拿到一份可读的总结。
 */
@Service
public class PlanSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(PlanSummaryGenerator.class);
    private static final Duration SUMMARY_TIMEOUT = Duration.ofSeconds(20);

    private static final String SYSTEM_PROMPT = """
            你是旅行规划师。根据给定的 JSON 资料，用中文写一段 150-300 字的旅行总结。
            要求：先说目的地与日期，再说每天怎么安排，然后说预算（总额、剩余、超支提醒），
            最后说天气与注意事项。只陈述资料里存在的事实，不要编造价格或地点。
            不要输出标题、不要 Markdown、不要列参考资料。""";

    private final DashScopeClient client;
    private final DashScopeProperties properties;
    private final ObjectMapper objectMapper;

    public PlanSummaryGenerator(
            DashScopeClient client, DashScopeProperties properties, ObjectMapper objectMapper) {
        this.client = client;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** @return {@code [summary, status]}，status 为 completed 或 fallback。 */
    public String[] generate(Map<String, Object> context) {
        try {
            String content = client.complete(
                    DashScopeClient.textBody(
                            properties.planningModel(),
                            SYSTEM_PROMPT,
                            toJson(context),
                            false,
                            null,
                            0.3),
                    SUMMARY_TIMEOUT);
            String text = content == null ? "" : content.strip();
            if (text.length() >= 40) {
                return new String[] {text, "completed"};
            }
        } catch (RuntimeException error) {
            log.warn("final summary failed, using local rules: {}", error.toString());
        }
        return new String[] {fallback(context), "fallback"};
    }

    /** 规则版：只用已经算好的数字，不猜。 */
    static String fallback(Map<String, Object> context) {
        Map<String, Object> trip = asMap(context.get("trip"));
        String destination = String.valueOf(trip.getOrDefault("destination", "目的地"));
        String start = String.valueOf(trip.getOrDefault("start_date", ""));
        String end = String.valueOf(trip.getOrDefault("end_date", ""));
        List<String> parts = new ArrayList<>();
        parts.add("这趟去" + destination + "的旅行安排在 " + start + " 至 " + end + "。");

        Object budget = context.get("budget");
        if (budget instanceof Map<?, ?> money) {
            Object total = money.get("total_estimate");
            Object remaining = money.get("remaining_budget");
            parts.add("目前估算花费约 " + total + " 元"
                    + (remaining == null ? "。" : "，剩余预算约 " + remaining + " 元。"));
        }
        Object conflicts = context.get("conflicts");
        if (conflicts instanceof List<?> list) {
            parts.add(list.isEmpty()
                    ? "行程里没有发现时间冲突。"
                    : "行程里有 " + list.size() + " 处时间重叠，建议调整：" + joinFirst(list, 3) + "。");
        }
        Object advice = context.get("weather_advice");
        if (advice instanceof List<?> list && !list.isEmpty()) {
            parts.add("有 " + list.size() + " 天的天气可能影响户外安排，出发前请再看一次预报。");
        }
        Object documents = context.get("traveler_documents");
        if (documents instanceof List<?> list && !list.isEmpty()) {
            parts.add("总结里已参考你上传的 " + list.size() + " 段资料。");
        }
        parts.add("以上数字按收藏地点与已排行程估算，实际费用以商家报价为准。");
        return String.join("", parts);
    }

    private static String joinFirst(List<?> items, int limit) {
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < Math.min(limit, items.size()); index++) {
            Object item = items.get(index);
            if (item instanceof Map<?, ?> map) {
                Object message = map.get("message");
                if (message != null) {
                    parts.add(String.valueOf(message));
                    continue;
                }
            }
            parts.add(String.valueOf(item));
        }
        return String.join("；", parts);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String toJson(Object value) {
        try {
            // 必须用 Spring 配置过的 ObjectMapper：上下文里有 LocalDate（每日建议的日期），
            // 裸 ObjectMapper 没有 JavaTimeModule，会抛异常后把整段上下文降级成 Map.toString()。
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            return String.valueOf(value);
        }
    }
}
