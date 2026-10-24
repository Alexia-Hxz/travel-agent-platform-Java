package com.huxizhen.travel.service;

import com.huxizhen.travel.integration.vision.VisionService;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * 用多模态视觉模型读图里的文字。
 *
 * <p>把 VisionService 的 503/422 语义透传出去：没配 key 就是"未配置"，模型失败就是"解析失败"，
 * 与原 OCR 的两类错误保持一致。
 */
@Component
public class VisionImageTextExtractor implements ImageTextExtractor {

    private final VisionService visionService;

    public VisionImageTextExtractor(VisionService visionService) {
        this.visionService = visionService;
    }

    @Override
    public String extract(Path filePath, String contentType) {
        return visionService.extractText(filePath, contentType);
    }
}
