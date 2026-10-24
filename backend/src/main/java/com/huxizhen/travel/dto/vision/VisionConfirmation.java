package com.huxizhen.travel.dto.vision;

/**
 * 用户对预览的确认。两个开关都可选，但"只存行程不存地点"是不合法的——
 * 行程项必须挂在某个地点上。
 */
public record VisionConfirmation(
        boolean savePlace,
        boolean saveItinerary,
        VisionPlaceSuggestion place,
        VisionItinerarySuggestion itinerary) {
}
