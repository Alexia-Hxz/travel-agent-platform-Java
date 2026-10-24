package com.huxizhen.travel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 旅迹 AI 后端入口。
 *
 * <p>与 Python 版 {@code app/main.py} 对应：FastAPI 的 lifespan、路由挂载、静态目录、
 * CORS 分别落在 {@code StartupRunner}、各 {@code Controller}、{@code WebConfig} 上。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TravelAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelAgentApplication.class, args);
    }
}
