package com.huxizhen.travel.dto.place;

import com.huxizhen.travel.entity.Place;
import java.time.LocalDate;

/** 收藏地点的对外视图。字段与 Pydantic {@code PlaceRead} 一一对应。 */
public record PlaceRead(
        Long id,
        Long tripId,
        String name,
        PlaceType type,
        String address,
        Double latitude,
        Double longitude,
        String imageUrl,
        Double rating,
        Integer priceLevel,
        String openingHours,
        String sourceUrl,
        String note,
        Integer estimatedCost,
        Integer quantity,
        LocalDate preferredDate) {

    public static PlaceRead from(Place place) {
        return new PlaceRead(
                place.getId(),
                place.getTripId(),
                place.getName(),
                PlaceType.fromDatabase(place.getType()),
                place.getAddress(),
                place.getLatitude(),
                place.getLongitude(),
                place.getImageUrl(),
                place.getRating(),
                place.getPriceLevel(),
                place.getOpeningHours(),
                place.getSourceUrl(),
                place.getNote(),
                place.getEstimatedCost(),
                place.getQuantity(),
                place.getPreferredDate());
    }
}
