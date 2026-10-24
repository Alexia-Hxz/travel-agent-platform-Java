package com.huxizhen.travel.dto.itinerary;

/** 放不进任何空档的收藏地点，带原因。 */
public record SkippedPlace(Long placeId, String placeName, String reason) {
}
