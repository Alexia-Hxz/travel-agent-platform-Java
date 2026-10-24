package com.huxizhen.travel.dto.itinerary;

/** 自动排期建议的一个时段。时间用字符串，和原实现一致（{@code HH:mm:ss}）。 */
public record ProposedItineraryItem(
        Long placeId,
        String placeName,
        String date,
        String startTime,
        String endTime,
        String note) {
}
