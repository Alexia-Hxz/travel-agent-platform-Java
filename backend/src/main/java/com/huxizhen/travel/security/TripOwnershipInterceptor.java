package com.huxizhen.travel.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huxizhen.travel.common.JsonResponses;
import com.huxizhen.travel.entity.Trip;
import com.huxizhen.travel.entity.User;
import com.huxizhen.travel.mapper.TripMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 「这趟旅行是不是你的」的统一闸门。
 *
 * <p>原项目把 {@code require_owned_trip} 挂在整组 router 上（itinerary / files / agent /
 * knowledge / adjustments），所以**任何**新增在 {@code /trips/{trip_id}/...} 下的路由都自动受保护。
 * 这里用同样的构造：一个拦截器匹配 {@code /api/v1/trips/*​/**}，路径这一段就是 trip_id。
 *
 * <p>别人家的 trip 与不存在的 trip **都回答 404**。403 会确认"这个 id 存在，只是不属于你"，
 * 那是调用方不该拥有的信息。
 */
@Component
public class TripOwnershipInterceptor implements HandlerInterceptor {

    public static final String TRIP_NOT_FOUND_DETAIL = "旅行项目不存在";

    private final TripMapper tripMapper;
    private final ObjectMapper objectMapper;

    public TripOwnershipInterceptor(TripMapper tripMapper, ObjectMapper objectMapper) {
        this.tripMapper = tripMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        Long tripId = tripIdOf(request.getRequestURI());
        if (tripId == null) {
            // 不是数字：让 Spring MVC 用自己的 422 回答（与 FastAPI 的路径参数转换错误同类）。
            return true;
        }
        User user = currentUser();
        if (user == null) {
            // 走到这里说明安全链已经放行，但上下文没用户；按未登录处理。
            JsonResponses.writeError(
                    objectMapper, response, HttpStatus.UNAUTHORIZED.value(), RestAuthenticationEntryPoint.UNAUTHORISED_DETAIL);
            return false;
        }
        Trip trip = tripMapper.selectById(tripId);
        if (trip == null || !user.getId().equals(trip.getUserId())) {
            JsonResponses.writeError(
                    objectMapper, response, HttpStatus.NOT_FOUND.value(), TRIP_NOT_FOUND_DETAIL);
            return false;
        }
        return true;
    }

    /** 从 {@code /api/v1/trips/{id}/...} 里取出 id；取不到返回 null。 */
    static Long tripIdOf(String uri) {
        if (uri == null) {
            return null;
        }
        String path = uri.startsWith("/") ? uri.substring(1) : uri;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String[] segments = path.split("/");
        if (segments.length < 5) {
            return null;
        }
        if (!"api".equals(segments[0]) || !"v1".equals(segments[1]) || !"trips".equals(segments[2])) {
            return null;
        }
        try {
            return Long.parseLong(segments[3]);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof User user ? user : null;
    }
}
