package com.huxizhen.travel.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.config.DashScopeProperties;
import com.huxizhen.travel.dto.plan.PlanDtos;
import com.huxizhen.travel.integration.llm.DashScopeClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 分类预算的「让模型给份额」那一步。
 *
 * <p>{@link BudgetPlanTool} 只负责把份额变成可展示的金额（最大余数法 + 合理性校验）；
 * 份额本身来自模型——它知道东京的住宿该占几成、东南亚的交通该省到什么程度。
 * 模型不可用或答案不合理时 {@link BudgetPlanTool#build} 会回落到默认份额并标成
 * {@code source=default}，所以这里失败不需要抛异常。
 */
@Service
public class BudgetPlanGenerator {

    private static final Logger log = LoggerFactory.getLogger(BudgetPlanGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> CATEGORIES =
            List.of("hotel", "restaurant", "attraction", "transport", "other");

    private static final String SYSTEM_PROMPT = """
            你是旅行预算规划师。用户会给出目的地、天数、人数、总预算和一个待分配金额，
            请把待分配金额按类别拆开。只输出 JSON 对象，不要任何解释、不要 Markdown 代码块。
            每个类别都必须给出正整数金额，所有类别之和必须恰好等于待分配金额。
            请结合该目的地的真实行情分配（例如日本住宿贵、市内交通便宜；东南亚住宿便宜）。
            经验区间：住宿 30%-55%、餐饮 15%-35%、景点门票 5%-30%、市内交通 3%-20%、
            其他（购物机动）不要超过 15%，当日往返可以不给住宿。
            输出键只能是 hotel、restaurant、attraction、transport、other。""";

    private final DashScopeClient client;
    private final DashScopeProperties properties;

    public BudgetPlanGenerator(DashScopeClient client, DashScopeProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 生成分类预算。模型不可用时直接走默认份额（{@code source=default}），不会失败。
     */
    public PlanDtos.BudgetPlanRead build(
            Integer tripBudget,
            int tripDays,
            Map<String, Object> travelerProfile,
            String destination) {
        if (!client.configured()) {
            return BudgetPlanTool.build(tripBudget, tripDays, travelerProfile, null);
        }
        Integer plannedTotal = BudgetPlanTool.plannedTotal(tripBudget);
        if (plannedTotal == null) {
            return BudgetPlanTool.build(tripBudget, tripDays, travelerProfile, null);
        }
        Map<String, Double> shares = askTheModel(
                destination, tripDays, people(travelerProfile), tripBudget, plannedTotal);
        return BudgetPlanTool.build(tripBudget, tripDays, travelerProfile, shares);
    }

    /** 一次小模型调用；任何失败都返回 null，让调用方回落默认份额。 */
    private Map<String, Double> askTheModel(
            String destination, int days, int people, Integer budget, int plannedTotal) {
        String user = "目的地：" + (destination == null ? "" : destination)
                + "\n天数：" + Math.max(1, days)
                + "\n人数：" + Math.max(1, people)
                + "\n总预算：" + budget + " CNY"
                + "\n待分配金额：" + plannedTotal + " CNY（总预算的 80%）";
        try {
            String content = client.complete(
                    DashScopeClient.textBody(
                            properties.planningModel(), SYSTEM_PROMPT, user, true, null, 0.2),
                    TIMEOUT);
            return parse(content);
        } catch (RuntimeException error) {
            log.warn("budget plan model call failed, falling back to default shares: {}", error.toString());
            return null;
        }
    }

    static Map<String, Double> parse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(content.strip());
            if (!root.isObject()) {
                return null;
            }
            Map<String, Double> values = new LinkedHashMap<>();
            for (String category : CATEGORIES) {
                JsonNode node = root.path(category);
                if (node.isNumber()) {
                    values.put(category, node.asDouble());
                    continue;
                }
                if (node.isTextual()) {
                    try {
                        values.put(category, Double.parseDouble(node.asText().trim()));
                    } catch (NumberFormatException ignored) {
                        // 这一类的答案不可用；isSane 会因为缺类而整体回落到默认份额
                    }
                }
            }
            return values.isEmpty() ? null : values;
        } catch (Exception error) {
            return null;
        }
    }

    private static int people(Map<String, Object> travelerProfile) {
        if (travelerProfile == null) {
            return 1;
        }
        Object value = travelerProfile.get("total_people");
        if (value == null) {
            return 1;
        }
        try {
            return Math.max(1, (int) Math.round(Double.parseDouble(String.valueOf(value))));
        } catch (RuntimeException error) {
            return 1;
        }
    }
}
