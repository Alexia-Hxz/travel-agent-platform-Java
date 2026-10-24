package com.huxizhen.travel.controller;

import com.huxizhen.travel.common.ApiException;
import com.huxizhen.travel.dto.trip.TripCreate;
import com.huxizhen.travel.dto.trip.TripRead;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.entity.User;
import com.huxizhen.travel.service.TripService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅行的增删查。
 *
 * <p>这里不能挂"整组 router"的归属校验——它同时服务 {@code /trips} 本身，而那个路径下
 * 没有可归属的 trip。所以每条路由自己调 {@link TripService#getOwned}。
 */
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    public ResponseEntity<TripRead> create(
            @Valid @RequestBody TripCreate payload, @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tripService.create(payload, user.getId()));
    }

    @GetMapping
    public List<TripRead> list(@AuthenticationPrincipal User user) {
        return tripService.list(user.getId());
    }

    @GetMapping("/{tripId}")
    public TripRead get(@PathVariable Long tripId, @AuthenticationPrincipal User user) {
        Trip trip = tripService.getOwned(tripId, user.getId());
        if (trip == null) {
            throw ApiException.notFound("旅行项目不存在");
        }
        return TripRead.from(trip);
    }

    @DeleteMapping("/{tripId}")
    public ResponseEntity<Void> delete(@PathVariable Long tripId, @AuthenticationPrincipal User user) {
        Trip trip = tripService.getOwned(tripId, user.getId());
        if (trip == null) {
            throw ApiException.notFound("旅行项目不存在");
        }
        tripService.delete(trip);
        return ResponseEntity.noContent().build();
    }
}
