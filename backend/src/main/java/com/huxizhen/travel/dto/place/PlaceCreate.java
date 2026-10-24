package com.huxizhen.travel.dto.place;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** 新增一个收藏地点。 */
public record PlaceCreate(
        @NotNull @Size(min = 1, max = 160) String name,
        @NotNull PlaceType type,
        @NotNull @Size(min = 1, max = 300) String address,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @Size(max = 500) String imageUrl,
        @DecimalMin("0.0") @DecimalMax("5.0") Double rating,
        @Min(0) @Max(5) Integer priceLevel,
        @Size(max = 300) String openingHours,
        @Size(max = 500) String sourceUrl,
        @Size(max = 600) String note,
        @Min(0) Integer estimatedCost,
        @Min(1) @Max(99) Integer quantity,
        LocalDate preferredDate) {
}
