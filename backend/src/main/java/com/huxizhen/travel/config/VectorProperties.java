package com.huxizhen.travel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量层配置。
 *
 * @param backend    {@code auto}（默认：先试 Milvus，连不上退到 MySQL 精确余弦）、
 *                   {@code milvus}、{@code mysql}
 * @param host       Milvus 地址
 * @param port       Milvus 端口
 * @param collection collection 名，与 Python 版保持一致
 * @param dim        向量维度（text-embedding-v4 是 1024）
 */
@ConfigurationProperties(prefix = "app.vector")
public record VectorProperties(
        String backend, String host, Integer port, String collection, Integer dim, Integer connectTimeoutMs) {

    public VectorProperties {
        if (backend == null || backend.isBlank()) {
            backend = "auto";
        }
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (port == null || port <= 0) {
            port = 19530;
        }
        if (collection == null || collection.isBlank()) {
            collection = "trip_documents";
        }
        if (dim == null || dim <= 0) {
            dim = 1024;
        }
        if (connectTimeoutMs == null || connectTimeoutMs <= 0) {
            connectTimeoutMs = 3000;
        }
    }

    public boolean useMysqlOnly() {
        return "mysql".equalsIgnoreCase(backend);
    }

    public boolean useMilvusOnly() {
        return "milvus".equalsIgnoreCase(backend);
    }
}
