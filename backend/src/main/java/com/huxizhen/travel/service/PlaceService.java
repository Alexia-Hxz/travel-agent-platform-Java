package com.huxizhen.travel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.huxizhen.travel.dto.place.PlaceCreate;
import com.huxizhen.travel.dto.place.PlaceRead;
import com.huxizhen.travel.dto.place.PlaceUpdate;
import com.huxizhen.travel.entity.Place;
import com.huxizhen.travel.mapper.PlaceMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 收藏地点的读写。 */
@Service
public class PlaceService {

    private final PlaceMapper placeMapper;

    public PlaceService(PlaceMapper placeMapper) {
        this.placeMapper = placeMapper;
    }

    public List<PlaceRead> list(Long tripId) {
        return placeMapper
                .selectList(Wrappers.<Place>lambdaQuery()
                        .eq(Place::getTripId, tripId)
                        .orderByAsc(Place::getId))
                .stream()
                .map(PlaceRead::from)
                .toList();
    }

    public List<Place> listEntities(Long tripId) {
        return placeMapper.selectList(Wrappers.<Place>lambdaQuery()
                .eq(Place::getTripId, tripId)
                .orderByAsc(Place::getId));
    }

    @Transactional
    public PlaceRead create(Long tripId, PlaceCreate payload) {
        Place place = new Place();
        place.setTripId(tripId);
        place.setName(payload.name());
        place.setType(payload.type().value());
        place.setAddress(payload.address());
        place.setLatitude(payload.latitude());
        place.setLongitude(payload.longitude());
        place.setImageUrl(payload.imageUrl());
        place.setRating(payload.rating());
        place.setPriceLevel(payload.priceLevel());
        place.setOpeningHours(payload.openingHours());
        place.setSourceUrl(payload.sourceUrl());
        place.setNote(payload.note());
        place.setEstimatedCost(payload.estimatedCost());
        place.setQuantity(payload.quantity());
        place.setPreferredDate(payload.preferredDate());
        placeMapper.insert(place);
        return PlaceRead.from(placeMapper.selectById(place.getId()));
    }

    public Place get(Long tripId, Long placeId) {
        return placeMapper.selectOne(Wrappers.<Place>lambdaQuery()
                .eq(Place::getId, placeId)
                .eq(Place::getTripId, tripId));
    }

    /**
     * 部分更新：只有客户端真的传了的字段才会被写，包括"显式传 null 清空"。
     *
     * <p>MyBatis-Plus 的 {@code updateById} 会跳过 null 字段，所以这里必须用
     * {@code UpdateWrapper#set} 逐列写。
     */
    @Transactional
    public PlaceRead update(Place place, PlaceUpdate payload) {
        var wrapper = Wrappers.<Place>lambdaUpdate().eq(Place::getId, place.getId());
        boolean touched = false;

        if (payload.isNamePresent()) {
            wrapper.set(Place::getName, payload.getName());
            touched = true;
        }
        if (payload.isTypePresent()) {
            wrapper.set(Place::getType, payload.getType() == null ? null : payload.getType().value());
            touched = true;
        }
        if (payload.isAddressPresent()) {
            wrapper.set(Place::getAddress, payload.getAddress());
            touched = true;
        }
        if (payload.isImageUrlPresent()) {
            wrapper.set(Place::getImageUrl, payload.getImageUrl());
            touched = true;
        }
        if (payload.isRatingPresent()) {
            wrapper.set(Place::getRating, payload.getRating());
            touched = true;
        }
        if (payload.isPriceLevelPresent()) {
            wrapper.set(Place::getPriceLevel, payload.getPriceLevel());
            touched = true;
        }
        if (payload.isOpeningHoursPresent()) {
            wrapper.set(Place::getOpeningHours, payload.getOpeningHours());
            touched = true;
        }
        if (payload.isNotePresent()) {
            wrapper.set(Place::getNote, payload.getNote());
            touched = true;
        }
        if (payload.isEstimatedCostPresent()) {
            wrapper.set(Place::getEstimatedCost, payload.getEstimatedCost());
            touched = true;
        }
        if (payload.isQuantityPresent()) {
            wrapper.set(Place::getQuantity, payload.getQuantity());
            touched = true;
        }
        if (payload.isPreferredDatePresent()) {
            wrapper.set(Place::getPreferredDate, payload.getPreferredDate());
            touched = true;
        }
        if (touched) {
            placeMapper.update(null, wrapper);
        }
        return PlaceRead.from(placeMapper.selectById(place.getId()));
    }

    @Transactional
    public void delete(Place place) {
        placeMapper.deleteById(place.getId());
    }

    /** 一次清空这趟旅行的全部收藏；行程项随外键级联一起走。返回删掉的数量。 */
    @Transactional
    public int clear(Long tripId) {
        List<Place> places = listEntities(tripId);
        if (!places.isEmpty()) {
            placeMapper.delete(Wrappers.<Place>lambdaQuery().eq(Place::getTripId, tripId));
        }
        return places.size();
    }
}
