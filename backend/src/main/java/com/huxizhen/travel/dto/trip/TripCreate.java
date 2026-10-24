package com.huxizhen.travel.dto.trip;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 新建一趟旅行。 */
public record TripCreate(
        @NotNull @Size(min = 1, max = 120) String title,
        @NotNull @Size(min = 1, max = 120) String destination,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @Min(0) Integer budget,
        @Min(0) Integer adultMaleCount,
        @Min(0) Integer adultFemaleCount,
        @Min(0) Integer childMaleCount,
        @Min(0) Integer childFemaleCount) {

    public TripCreate {
        // 人数默认 0：前端不带这几个字段时也要落成 0，而不是 null。
        adultMaleCount = adultMaleCount == null ? 0 : adultMaleCount;
        adultFemaleCount = adultFemaleCount == null ? 0 : adultFemaleCount;
        childMaleCount = childMaleCount == null ? 0 : childMaleCount;
        childFemaleCount = childFemaleCount == null ? 0 : childFemaleCount;
    }
}
