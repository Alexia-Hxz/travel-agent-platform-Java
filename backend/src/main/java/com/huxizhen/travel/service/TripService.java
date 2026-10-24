package com.huxizhen.travel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.dto.trip.TripCreate;
import com.huxizhen.travel.dto.trip.TripRead;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.mapper.TripMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 旅行的读写。
 *
 * <p>{@link #getOwned} 是"别人家的旅行"与"不存在的旅行"合并成同一个 {@code null} 的地方，
 * 调用方把它变成同一个 404——API 永远不确认某个 id 是否存在于别人的账号下。
 */
@Service
public class TripService {

    private final TripMapper tripMapper;
    private final FileStorageService fileStorageService;

    public TripService(TripMapper tripMapper, FileStorageService fileStorageService) {
        this.tripMapper = tripMapper;
        this.fileStorageService = fileStorageService;
    }

    @Transactional
    public TripRead create(TripCreate payload, Long userId) {
        if (payload.endDate().isBefore(payload.startDate())) {
            throw ApiException.unprocessable("结束日期不能早于出发日期");
        }
        Trip trip = new Trip();
        trip.setUserId(userId);
        trip.setTitle(payload.title());
        trip.setDestination(payload.destination());
        trip.setStartDate(payload.startDate());
        trip.setEndDate(payload.endDate());
        trip.setBudget(payload.budget());
        trip.setAdultMaleCount(payload.adultMaleCount());
        trip.setAdultFemaleCount(payload.adultFemaleCount());
        trip.setChildMaleCount(payload.childMaleCount());
        trip.setChildFemaleCount(payload.childFemaleCount());
        tripMapper.insert(trip);
        return TripRead.from(tripMapper.selectById(trip.getId()));
    }

    /** 本账号的旅行，出发日期早的在前。 */
    public List<TripRead> list(Long userId) {
        return tripMapper
                .selectList(Wrappers.<Trip>lambdaQuery()
                        .eq(Trip::getUserId, userId)
                        .orderByAsc(Trip::getStartDate)
                        .orderByAsc(Trip::getId))
                .stream()
                .map(TripRead::from)
                .toList();
    }

    public Trip get(Long tripId) {
        return tripMapper.selectById(tripId);
    }

    public Trip getOwned(Long tripId, Long userId) {
        Trip trip = tripMapper.selectById(tripId);
        if (trip == null || userId == null || !userId.equals(trip.getUserId())) {
            return null;
        }
        return trip;
    }

    /**
     * 删除旅行。
     *
     * <p>顺序与原实现一致：先删文件（向量 → 磁盘 → 行），再删旅行本身；外键级联带走
     * 收藏地点、行程项与调整草案。
     */
    @Transactional
    public void delete(Trip trip) {
        fileStorageService.deleteForTrip(trip.getId());
        tripMapper.deleteById(trip.getId());
    }
}
