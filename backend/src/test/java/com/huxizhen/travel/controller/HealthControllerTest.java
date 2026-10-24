package com.huxizhen.travel.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 健康检查的单元测试。
 *
 * <p>HTTP 层（状态码 200、公开可访问）由 {@code tools/api_smoke.py} 覆盖；
 * 这里只锁住响应体形状，避免切片测试去加载整条安全链与 MVC 装配。
 */
class HealthControllerTest {

    private final HealthController controller = new HealthController();

    @Test
    void healthReturnsOk() {
        assertThat(controller.health()).containsEntry("status", "ok");
    }
}
