package com.huxizhen.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地存储位置。
 *
 * @param projectRoot 项目根目录（Python 里是 {@code backend/}）。数据库里 {@code file_path}
 *                    存的是相对它的路径，形如 {@code data/uploads/12/xxx.png}。
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(String projectRoot) {

    public StorageProperties {
        if (projectRoot == null || projectRoot.isBlank()) {
            projectRoot = ".";
        }
    }
}
