package com.huxizhen.travel.dto.itinerary;

import java.util.List;

/** 自动排期的结果。只是预览：调用方仍要通过正常的行程项接口落库。 */
public record AutoScheduleRead(
        Long tripId,
        List<ProposedItineraryItem> proposed,
        List<SkippedPlace> skipped,
        List<String> alreadyScheduled,
        String message) {
}
