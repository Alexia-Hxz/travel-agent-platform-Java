package com.huxizhen.travel.dto.vision;

import com.huxizhen.travel.dto.itinerary.ItineraryItemRead;
import com.huxizhen.travel.dto.place.PlaceRead;

/** 确认入库后的结果：存了什么就回什么。 */
public record VisionConfirmationResult(PlaceRead place, ItineraryItemRead itinerary) {
}
