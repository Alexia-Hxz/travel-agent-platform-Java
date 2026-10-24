package com.huxizhen.travel.integration.search;

import com.huxizhen.travel.config.DashScopeProperties;
import com.huxizhen.travel.integration.llm.DashScopeClient;
import com.huxizhen.travel.integration.llm.LlmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 联网检索：走模型自己的实时搜索（{@code enable_search}）。
 *
 * <p>原项目曾有一个按次计费的外部搜索 API，实测对每个查询都返回 HTTP 432，早已移除。
 * 现在唯一入口就是模型自带的联网能力——它已经在付费范围内、中文回答、带实时价格。
 *
 * <p>返回的是**一段答案**而不是一组网页：兼容模式只暴露最终回答，没有结果 URL。
 * 这对规划够用——素材只被用来抽取地点名与价格，图片另有来源。
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(120);
    private static final int SEARCH_MAX_TOKENS = 2000;
    public static final int MIN_ANSWER_CHARS = 40;
    private static final int PRICE_ANSWER_MIN_CHARS = 6;

    private static final String[] DISCLAIMER_MARKERS = {
        "无法实时联网", "无法访问互联网", "无法联网", "不能联网", "没有联网", "未联网",
        "无法获取实时", "无法获取最新", "知识截止", "知识库截止", "训练数据截止"
    };

    private static final String NO_DISCLAIMER_RULE =
            "本次请求已开启联网检索，你可以直接获取实时信息。"
                    + "不要声明自己无法联网，不要提及知识截止时间，也不要写任何免责声明或能力说明，"
                    + "直接给出检索到的事实。";

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?；;])\\s*");

    private final DashScopeClient client;
    private final DashScopeProperties properties;

    public WebSearchService(DashScopeClient client, DashScopeProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public boolean searchAvailable() {
        return client.configured();
    }

    /** 检索结果：一��� answer 形状的结果 + 状态串（原实现直接把状态当消息用）。 */
    public record SearchResult(List<Map<String, Object>> results, String status) {
    }

    public SearchResult searchWeb(String query, String destination) {
        if (!searchAvailable()) {
            return new SearchResult(List.of(), "fallback: live search is not configured");
        }
        String answer = groundedAnswer(
                destination + "：" + query + "\n\n请给出具体的景点、餐厅、酒店名称和价格等事实信息，名称一律用中文。",
                MIN_ANSWER_CHARS,
                SEARCH_TIMEOUT);
        if (answer == null) {
            return new SearchResult(List.of(), "fallback: live search returned no usable answer");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_type", "web_answer");
        result.put("title", "联网检索摘要");
        result.put("url", null);
        result.put("snippet", answer);
        result.put("score", null);
        result.put("image_url", null);
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(result);
        return new SearchResult(results, "completed");
    }

    /** 单个价格的短查询：只要求答案里有数字，不套用规划素材的 40 字门槛。 */
    public List<String> searchSnippets(String query, double timeoutSeconds) {
        if (!searchAvailable()) {
            return List.of();
        }
        try {
            String answer = groundedAnswer(
                    query + "\n\n请给出具体价格数字和币种（例如「人均 1200 日元」「门票 600 日元」），"
                            + "只写事实，不要输出多余解释。",
                    PRICE_ANSWER_MIN_CHARS,
                    Duration.ofMillis((long) (timeoutSeconds * 1000)));
            if (answer == null || answer.chars().noneMatch(Character::isDigit)) {
                return List.of();
            }
            return List.of(answer);
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    /** 一次联网检索，返回去掉免责声明后的答案；任何失败都返回 null（调用方自带降级）。 */
    public String groundedAnswer(String query, int minChars, Duration timeout) {
        if (!searchAvailable()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.planningModel());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", "请先联网检索，然后用中文回答下面的问题，尽量给出具体地名、店名和价格。\n"
                        + NO_DISCLAIMER_RULE + "\n\n" + query)));
        body.put("enable_search", true);
        body.put("enable_thinking", false);
        body.put("temperature", 0.1);
        body.put("max_tokens", SEARCH_MAX_TOKENS);

        String content;
        try {
            content = client.complete(body, timeout);
        } catch (LlmException error) {
            log.warn("live search call failed: {}", error.getMessage());
            return null;
        }
        if (content == null || content.isBlank()) {
            return null;
        }
        String cleaned = stripDisclaimers(content);
        if (cleaned.length() < minChars) {
            log.warn("live search answer had no usable content after stripping disclaimers ({} chars < {})",
                    cleaned.length(), minChars);
            return null;
        }
        return cleaned;
    }

    /**
     * 去掉"我无法联网"这类与事实相反的开场白。
     *
     * <p>按**行**处理：免责声明通常只是一段开头，后面紧跟的表格行没有句号，按整段切句
     * 会把免责声明和整张表粘成一句、一起删掉。
     */
    static String stripDisclaimers(String text) {
        List<String> kept = new ArrayList<>();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                kept.add("");
                continue;
            }
            List<String> sentences = new ArrayList<>();
            for (String sentence : SENTENCE_SPLIT.split(stripped)) {
                if (sentence.isBlank() || containsDisclaimer(sentence)) {
                    continue;
                }
                sentences.add(sentence);
            }
            kept.add(String.join(" ", sentences).strip());
        }
        return String.join("\n", kept).strip();
    }

    private static boolean containsDisclaimer(String sentence) {
        for (String marker : DISCLAIMER_MARKERS) {
            if (sentence.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
