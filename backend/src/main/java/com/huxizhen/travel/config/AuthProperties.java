package com.huxizhen.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证相关配置。
 *
 * @param secretKey       为空时 TokenService 使用内置开发密钥（与原项目行为一致）
 * @param tokenTtlSeconds token 有效期，默认 30 天
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(String secretKey, Long tokenTtlSeconds) {

    public AuthProperties {
        if (tokenTtlSeconds == null || tokenTtlSeconds <= 0) {
            tokenTtlSeconds = 30L * 24 * 60 * 60;
        }
    }
}
