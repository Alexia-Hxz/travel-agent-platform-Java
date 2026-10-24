package com.huxizhen.travel.dto.itinerary;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/** 往某一天排一个行程项。 */
public record ItineraryItemCreate(
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull Long placeId,
        @Size(max = 500) String note,
        @Min(0) Integer sortOrder) {

    public ItineraryItemCreate {
        sortOrder = sortOrder == null ? 0 : sortOrder;
    }
}
