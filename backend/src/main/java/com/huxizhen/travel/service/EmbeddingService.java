package com.huxizhen.travel.service;

import com.huxizhen.travel.config.DashScopeProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * 文本向量化（R1 = C：Embedding 走 Spring AI 的 {@link EmbeddingModel}）。
 *
 * <p>分批、顺序与维度校验**仍由我们自己做**，不交给框架：
 * <ul>
 *   <li>DashScope 对单批超过 10 条的请求直接回 HTTP 400，必须按 10 条切；</li>
 *   <li>返回顺序不保证，要按批内顺序还原（Spring AI 已按 index 对齐，这里再验数量）；</li>
 *   <li>维度必须是 1024，否则写进库里就永远检索不到。</li>
 * </ul>
 */
@Service
public class EmbeddingService {

    public static final int MAX_BATCH_SIZE = 10;
    public static final int MAX_INPUT_CHARS = 4000;

    private final EmbeddingModel embeddingModel;
    private final DashScopeProperties properties;

    public EmbeddingService(EmbeddingModel embeddingModel, DashScopeProperties properties) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    public List<float[]> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!properties.configured()) {
            throw new EmbeddingConfigurationException(
                    "没有设置 DASHSCOPE_API_KEY，上传的资料无法建立向量索引。");
        }
        List<String> prepared = new ArrayList<>(texts.size());
        for (String text : texts) {
            prepared.add(text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text);
        }

        List<float[]> vectors = new ArrayList<>(prepared.size());
        for (int start = 0; start < prepared.size(); start += MAX_BATCH_SIZE) {
            List<String> batch = prepared.subList(start, Math.min(start + MAX_BATCH_SIZE, prepared.size()));
            List<float[]> embedded;
            try {
                embedded = embeddingModel.embed(batch);
            } catch (RuntimeException error) {
                throw new EmbeddingException("向量化服务拒绝了请求：" + error.getMessage(), error);
            }
            if (embedded == null || embedded.size() != batch.size()) {
                throw new EmbeddingException("向量化服务返回的向量数量不对："
                        + (embedded == null ? 0 : embedded.size()) + " vectors for " + batch.size() + " inputs.");
            }
            for (float[] vector : embedded) {
                if (vector.length != properties.embeddingDim()) {
                    throw new EmbeddingException(properties.embeddingModel() + " returned a "
                            + vector.length + "-dimension vector but the collection holds "
                            + properties.embeddingDim() + "-dimension vectors. Rebuild the document index or"
                            + " correct EMBEDDING_DIM.");
                }
                vectors.add(vector);
            }
        }
        return vectors;
    }

    /** 向量化失败。 */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 没有 Key，连请求都发不出去。 */
    public static class EmbeddingConfigurationException extends EmbeddingException {
        public EmbeddingConfigurationException(String message) {
            super(message);
        }
    }
}
