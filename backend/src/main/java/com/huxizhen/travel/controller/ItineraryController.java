package com.huxizhen.travel.controller;

import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.dto.itinerary.AutoScheduleRead;
import com.huxizhen.travel.dto.itinerary.ItineraryItemCreate;
import com.huxizhen.travel.dto.itinerary.ItineraryItemRead;
import com.huxizhen.travel.dto.place.PlaceCreate;
import com.huxizhen.travel.dto.place.PlaceRead;
import com.huxizhen.travel.dto.place.PlaceUpdate;
import com.huxizhen.travel.entity.ItineraryItem;
import com.huxizhen.travel.entity.Place;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.service.AutoScheduleService;
import com.huxizhen.travel.service.ItineraryService;
import com.huxizhen.travel.service.PlaceService;
import com.huxizhen.travel.service.TripService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 收藏地点与每日行程项。
 *
 * <p>所有路由都在 {@code /trips/{trip_id}/...} 下，归属校验由
 * {@code TripOwnershipInterceptor} 统一完成（与原项目把依赖挂在整组 router 上等价）。
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}")
public class ItineraryController {

    private final TripService tripService;
    private final PlaceService placeService;
    private final ItineraryService itineraryService;
    private final AutoScheduleService autoScheduleService;

    public ItineraryController(
            TripService tripService,
            PlaceService placeService,
            ItineraryService itineraryService,
            AutoScheduleService autoScheduleService) {
        this.tripService = tripService;
        this.placeService = placeService;
        this.itineraryService = itineraryService;
        this.autoScheduleService = autoScheduleService;
    }

    // ---------------------------------------------------------------- places

    @GetMapping("/places")
    public List<PlaceRead> listPlaces(@PathVariable Long tripId) {
        requireTrip(tripId);
        return placeService.list(tripId);
    }

    @PostMapping("/places")
    public ResponseEntity<PlaceRead> createPlace(
            @PathVariable Long tripId, @Valid @RequestBody PlaceCreate payload) {
        requireTrip(tripId);
        return ResponseEntity.status(HttpStatus.CREATED).body(placeService.create(tripId, payload));
    }

    /** 改价格、改次数、改细节，不必删了重加。 */
    @PatchMapping("/places/{placeId}")
    public PlaceRead updatePlace(
            @PathVariable Long tripId,
            @PathVariable Long placeId,
            @Valid @RequestBody PlaceUpdate payload) {
        requireTrip(tripId);
        Place place = placeService.get(tripId, placeId);
        if (place == null) {
            throw ApiException.notFound("收藏地点不存在");
        }
        return placeService.update(place, payload);
    }

    /** 一次清空全部收藏；返回删掉的数量（不是 204）。 */
    @DeleteMapping("/places")
    public Map<String, Integer> clearPlaces(@PathVariable Long tripId) {
        requireTrip(tripId);
        return Map.of("removed", placeService.clear(tripId));
    }

    @DeleteMapping("/places/{placeId}")
    public ResponseEntity<Void> deletePlace(@PathVariable Long tripId, @PathVariable Long placeId) {
        requireTrip(tripId);
        Place place = placeService.get(tripId, placeId);
        if (place == null) {
            throw ApiException.notFound("收藏地点不存在");
        }
        placeService.delete(place);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------- itinerary

    @GetMapping("/itinerary-items")
    public List<ItineraryItemRead> listItems(@PathVariable Long tripId) {
        requireTrip(tripId);
        return itineraryService.list(tripId);
    }

    @PostMapping("/itinerary-items")
    public ResponseEntity<ItineraryItemRead> createItem(
            @PathVariable Long tripId, @Valid @RequestBody ItineraryItemCreate payload) {
        Trip trip = requireTrip(tripId);
        if (payload.endTime().isBefore(payload.startTime())) {
            throw ApiException.unprocessable("结束时间不能早于开始时间");
        }
        ItineraryItemRead item = itineraryService.create(trip, payload);
        if (item == null) {
            throw ApiException.unprocessable("行程日期或地点无效");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(item);
    }

    /**
     * 给每个还没排期的收藏地点建议一天一个时段。
     *
     * <p>只读：调用方拿到建议后仍走正常的行程项接口，写入路径只有一条、且都经过校验。
     */
    @PostMapping("/itinerary-items/auto-schedule")
    public AutoScheduleRead autoSchedule(@PathVariable Long tripId) {
        Trip trip = requireTrip(tripId);
        List<Place> places = placeService.listEntities(tripId);
        List<ItineraryItem> items = itineraryService.listEntities(tripId);
        List<AutoScheduleService.ExistingSlot> existing = items.stream()
                .map(item -> new AutoScheduleService.ExistingSlot(
                        item.getPlaceId(), item.getDate(), item.getStartTime(), item.getEndTime()))
                .toList();
        AutoScheduleService.Proposal proposal =
                autoScheduleService.propose(places, existing, trip.getStartDate(), trip.getEndDate());

        String message;
        if (!proposal.proposed().isEmpty()) {
            message = "已为 " + proposal.proposed().size() + " 个收藏地点找到合适的时段。";
        } else if (!proposal.alreadyScheduled().isEmpty()) {
            message = "收藏地点都已经排进行程了。";
        } else {
            message = "这些收藏地点暂时找不到合适的时段，请手动安排。";
        }
        if (!proposal.skipped().isEmpty()) {
            message += " 有 " + proposal.skipped().size() + " 个地点放不下。";
        }
        return new AutoScheduleRead(
                tripId, proposal.proposed(), proposal.skipped(), proposal.alreadyScheduled(), message);
    }

    @DeleteMapping("/itinerary-items/{itemId}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long tripId, @PathVariable Long itemId) {
        requireTrip(tripId);
        ItineraryItem item = itineraryService.get(tripId, itemId);
        if (item == null) {
            throw ApiException.notFound("行程项不存在");
        }
        itineraryService.delete(item);
        return ResponseEntity.noContent().build();
    }

    private Trip requireTrip(Long tripId) {
        Trip trip = tripService.get(tripId);
        if (trip == null) {
            throw ApiException.notFound("旅行项目不存在");
        }
        return trip;
    }
}
