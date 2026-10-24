package com.huxizhen.travel.dto.trip;

import com.huxizhen.travel.entity.Trip;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 旅行的对外视图。 */
public record TripRead(
        Long id,
        String title,
        String destination,
        LocalDate startDate,
        LocalDate endDate,
        Integer budget,
        Integer adultMaleCount,
        Integer adultFemaleCount,
        Integer childMaleCount,
        Integer childFemaleCount,
        LocalDateTime createdAt) {

    public static TripRead from(Trip trip) {
        return new TripRead(
                trip.getId(),
                trip.getTitle(),
                trip.getDestination(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getBudget(),
                trip.getAdultMaleCount(),
                trip.getAdultFemaleCount(),
                trip.getChildMaleCount(),
                trip.getChildFemaleCount(),
                trip.getCreatedAt());
    }
}
