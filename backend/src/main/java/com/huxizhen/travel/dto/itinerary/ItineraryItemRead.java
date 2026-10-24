package com.huxizhen.travel.dto.itinerary;

import com.huxizhen.travel.dto.place.PlaceRead;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 行程项的对外视图。
 *
 * <p>内嵌的 {@code place} 不是可选的：前端用它渲染名字、图片、地址、类型。少这一层，
 * 页面就是空白。
 */
public record ItineraryItemRead(
        Long id,
        Long tripId,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        Long placeId,
        String note,
        Integer sortOrder,
        PlaceRead place) {
}
