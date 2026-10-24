package com.huxizhen.travel.config;

import com.huxizhen.travel.service.vector.MilvusVectorStore;
import com.huxizhen.travel.service.vector.MysqlVectorStore;
import com.huxizhen.travel.service.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 选择向量后端。
 *
 * <p>默认 {@code auto}：先连 Milvus（R5 选定方案），连不上就退到 MySQL 精确余弦，
 * 并在启动日志里说清楚。{@code milvus} 是严格模式：连不上就保持不可用，由
 * {@code /knowledge/status} 报告原因，绝不悄悄换后端。
 *
 * <p>为什么要有 MySQL 退路：Milvus 需要 etcd + minio + milvus 三容器，而开发机上
 * Docker Hub 不可达、minio 镜像被镜像站拒绝。MySQL 精确余弦与 Chroma 的数学定义一致，
 * 是本场景最可复现的等价物，也让检索行为可以被真实验证。
 */
@Configuration
public class VectorConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorConfig.class);

    @Bean(destroyMethod = "close")
    public VectorStore vectorStore(VectorProperties properties, JdbcTemplate jdbcTemplate) {
        MysqlVectorStore mysql = new MysqlVectorStore(jdbcTemplate, properties);
        if (properties.useMysqlOnly()) {
            log.info("向量后端：MySQL 精确余弦（app.vector.backend=mysql）");
            return mysql;
        }

        MilvusVectorStore milvus = new MilvusVectorStore(properties);
        milvus.ensureReady();
        if (milvus.available()) {
            log.info("向量后端：Milvus {}", milvus.describePath());
            return milvus;
        }
        if (properties.useMilvusOnly()) {
            log.warn("向量后端：Milvus 不可用（{}），按配置不做回退", milvus.unavailableReason());
            return milvus;
        }
        log.warn("向量后端：Milvus 不可用（{}），回退到 MySQL 精确余弦", milvus.unavailableReason());
        milvus.close();
        return mysql;
    }
}
