package com.huxizhen.travel.integration.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.config.DashScopeProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * DashScope 兼容模式的对话客户端。
 *
 * <p>为什么不用 Spring AI 的 ChatClient：DashScope 的 {@code enable_search} 是私有扩展参数，
 * 框架封装不保证透传；而各个调用点的超时、模型、{@code max_tokens}、{@code response_format}
 * 都不一样（30s / 60s / 90s / 120s）。这里直接把请求体交给 HTTP 层，参数一个不丢。
 *
 * <p>失败语义与原实现一致：**抛异常**，由调用方决定是降级还是报错。
 */
@Component
public class DashScopeClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final String apiKey;
    private final boolean configured;

    public DashScopeClient(DashScopeProperties properties) {
        this.apiKey = properties.apiKey();
        this.configured = properties.configured();
        this.webClient = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    public boolean configured() {
        return configured;
    }

    /**
     * 发一次对话请求，返回 {@code choices[0].message.content}。
     *
     * <p>{@code body} 原样发送：调用方自己塞 {@code model / messages / temperature /
     * max_tokens / response_format / enable_search}，这样私有参数不会被框架吞掉。
     */
    public String complete(Map<String, Object> body, Duration timeout) throws LlmException {
        if (!configured) {
            throw new LlmException("还没有配置 DASHSCOPE_API_KEY。");
        }
        try {
            return post(body, timeout);
        } catch (WebClientResponseException error) {
            if (error.getStatusCode().is4xxClientError()) {
                throw new LlmException(
                        "模型服务拒绝了请求：" + error.getStatusCode() + " " + abbreviate(error.getResponseBodyAsString()),
                        error);
            }
            return retryOnce(body, timeout, error);
        } catch (RuntimeException error) {
            return retryOnce(body, timeout, error);
        }
    }

    private String retryOnce(Map<String, Object> body, Duration timeout, RuntimeException first) {
        log.debug("model call failed, retrying once: {}", first.toString());
        try {
            return post(body, timeout);
        } catch (RuntimeException error) {
            throw new LlmException("模型服务调用失败：" + error.getMessage(), error);
        }
    }

    private String post(Map<String, Object> body, Duration timeout) {
        String raw = webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(timeout);
        return contentOf(raw);
    }

    private static String contentOf(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmException("模型服务返回了空响应");
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new LlmException("模型响应里没有 choices：" + abbreviate(raw));
            }
            return choices.get(0).path("message").path("content").asText("");
        } catch (LlmException error) {
            throw error;
        } catch (Exception error) {
            throw new LlmException("模型响应无法解析：" + abbreviate(raw), error);
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }

    /** 便捷构造：system + user 两条消息。 */
    public static Map<String, Object> textBody(
            String model,
            String system,
            String user,
            boolean jsonMode,
            Integer maxTokens,
            Double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        return body;
    }
}
