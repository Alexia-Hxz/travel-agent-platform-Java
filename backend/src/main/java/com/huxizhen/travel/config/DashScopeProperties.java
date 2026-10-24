package com.huxizhen.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DashScope（兼容 OpenAI 协议）配置。
 *
 * @param apiKey        为空时所有模型能力降级（与原项目"没有 key 就返回 null 客户端"一致）
 * @param baseUrl       兼容模式地址
 * @param planningModel 规划/抽取用模型
 * @param visionModel   视觉模型（多模态解析）
 * @param embeddingModel 向量化模型
 * @param embeddingDim   向量维度；换模型必须重建索引
 */
@ConfigurationProperties(prefix = "app.dashscope")
public record DashScopeProperties(
        String apiKey,
        String baseUrl,
        String planningModel,
        String visionModel,
        String embeddingModel,
        Integer embeddingDim) {

    public DashScopeProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        if (planningModel == null || planningModel.isBlank()) {
            planningModel = "qwen3.7-plus";
        }
        if (visionModel == null || visionModel.isBlank()) {
            // 原项目写死 qwen3-vl-flash，但该模型在 DashScope 上已不存在（404 model_not_found）；
            // 这里用当前可用的 qwen3-vl-plus，并允许用 VISION_MODEL_NAME 覆盖。
            visionModel = "qwen3-vl-plus";
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            embeddingModel = "text-embedding-v4";
        }
        if (embeddingDim == null || embeddingDim <= 0) {
            embeddingDim = 1024;
        }
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
