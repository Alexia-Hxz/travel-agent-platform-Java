package com.huxizhen.travel.service;

import java.nio.file.Path;

/**
 * 从一张图片里读出文字。
 *
 * <p>原实现是 PaddleOCR（只装了英文模型）。这里默认用多模态视觉模型，理由是：中文截图能识别、
 * 不需要原生 ONNX 运行库、跨平台无坑。要换回本地 OCR 时只需新增一个实现，调用方不用改。
 */
public interface ImageTextExtractor {

    String extract(Path filePath, String contentType);
}
