package com.huxizhen.travel.dto.vision;

/** 视觉解析的预览结果：先给用户看，确认后才入库。 */
public record VisionPreview(
        VisionDocumentType documentType,
        String summary,
        VisionPlaceSuggestion place,
        VisionItinerarySuggestion itinerary,
        String referenceNumber,
        String rawText) {
}
