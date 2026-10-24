package com.huxizhen.travel.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查，对应 Python 版 {@code @app.get("/api/v1/health")}。
 *
 * <p>Docker Compose 的 healthcheck 与前端"后端是否启动"的判断都打这里。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
