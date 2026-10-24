package com.huxizhen.travel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.huxizhen.travel.dto.itinerary.ItineraryItemCreate;
import com.huxizhen.travel.dto.itinerary.ItineraryItemRead;
import com.huxizhen.travel.dto.place.PlaceRead;
import com.huxizhen.travel.entity.ItineraryItem;
import com.huxizhen.travel.entity.Place;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.mapper.ItineraryItemMapper;
import com.huxizhen.travel.mapper.PlaceMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 行程项的读写。 */
@Service
public class ItineraryService {

    private final ItineraryItemMapper itemMapper;
    private final PlaceMapper placeMapper;
    private final PlaceService placeService;

    public ItineraryService(
            ItineraryItemMapper itemMapper, PlaceMapper placeMapper, PlaceService placeService) {
        this.itemMapper = itemMapper;
        this.placeMapper = placeMapper;
        this.placeService = placeService;
    }

    /** 排序与原实现一致：日期 → sort_order → 开始时间 → id。 */
    public List<ItineraryItem> listEntities(Long tripId) {
        return itemMapper.selectList(Wrappers.<ItineraryItem>lambdaQuery()
                .eq(ItineraryItem::getTripId, tripId)
                .orderByAsc(ItineraryItem::getDate)
                .orderByAsc(ItineraryItem::getSortOrder)
                .orderByAsc(ItineraryItem::getStartTime)
                .orderByAsc(ItineraryItem::getId));
    }

    public List<ItineraryItemRead> list(Long tripId) {
        List<ItineraryItem> items = listEntities(tripId);
        if (items.isEmpty()) {
            return List.of();
        }
        List<Long> placeIds = items.stream()
                .map(ItineraryItem::getPlaceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Place> places = new HashMap<>();
        if (!placeIds.isEmpty()) {
            for (Place place : placeMapper.selectBatchIds(placeIds)) {
                places.put(place.getId(), place);
            }
        }
        return items.stream()
                .map(item -> toRead(item, places.get(item.getPlaceId())))
                .toList();
    }

    /**
     * 排一个行程项。日期不在旅行区间、或地点不属于这趟旅行时返回 {@code null}，
     * 由调用方转成 422「行程日期或地点无效」。
     */
    @Transactional
    public ItineraryItemRead create(Trip trip, ItineraryItemCreate payload) {
        if (payload.date().isBefore(trip.getStartDate()) || payload.date().isAfter(trip.getEndDate())) {
            return null;
        }
        Place place = placeService.get(trip.getId(), payload.placeId());
        if (place == null) {
            return null;
        }
        ItineraryItem item = new ItineraryItem();
        item.setTripId(trip.getId());
        item.setDate(payload.date());
        item.setStartTime(payload.startTime());
        item.setEndTime(payload.endTime());
        item.setPlaceId(payload.placeId());
        item.setNote(payload.note());
        item.setSortOrder(payload.sortOrder());
        itemMapper.insert(item);
        return toRead(itemMapper.selectById(item.getId()), place);
    }

    public ItineraryItem get(Long tripId, Long itemId) {
        return itemMapper.selectOne(Wrappers.<ItineraryItem>lambdaQuery()
                .eq(ItineraryItem::getId, itemId)
                .eq(ItineraryItem::getTripId, tripId));
    }

    @Transactional
    public void delete(ItineraryItem item) {
        itemMapper.deleteById(item.getId());
    }

    public ItineraryItemRead toRead(ItineraryItem item, Place place) {
        return new ItineraryItemRead(
                item.getId(),
                item.getTripId(),
                item.getDate(),
                item.getStartTime(),
                item.getEndTime(),
                item.getPlaceId(),
                item.getNote(),
                item.getSortOrder(),
                place == null ? null : PlaceRead.from(place));
    }
}
