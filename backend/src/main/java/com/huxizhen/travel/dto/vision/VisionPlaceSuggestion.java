package com.huxizhen.travel.dto.vision;

import com.huxizhen.travel.dto.place.PlaceType;

/** 图片里识别出的一个地点。 */
public record VisionPlaceSuggestion(
        String name,
        PlaceType type,
        String address,
        Double latitude,
        Double longitude,
        String imageUrl,
        Double rating,
        Integer priceLevel,
        String openingHours,
        String sourceUrl) {
}
