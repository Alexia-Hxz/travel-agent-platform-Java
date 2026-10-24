-- 向量片段的 MySQL 存储（仅当 app.vector.backend=mysql / auto 且 Milvus 不可用时使用）。
--
-- 为什么会有这张表：Milvus 需要一个 etcd + minio + milvus 三容器栈，而本机 Docker Hub
-- 不可达、minio 镜像被镜像站拒绝。MySQL 精确余弦与 Chroma 的数学定义完全一致
-- （相似度 = 余弦，不是 1-distance），在本场景（千级片段）毫秒级返回。
--
-- 表结构与 Milvus collection 的字段一一对应，切换后端不需要改业务代码。

CREATE TABLE `trip_document_chunks` (
    `chunk_id`        VARCHAR(128) NOT NULL,
    `trip_id`         BIGINT       NOT NULL,
    `file_id`         BIGINT       NOT NULL,
    `chunk_index`     INT          NOT NULL,
    `source`          VARCHAR(512) NOT NULL,
    `text`            MEDIUMTEXT   NOT NULL,
    `char_start`      INT          NOT NULL DEFAULT 0,
    `char_end`        INT          NOT NULL DEFAULT 0,
    `embedding_model` VARCHAR(128) NOT NULL,
    `embedding`       BLOB         NOT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`chunk_id`),
    KEY `ix_chunks_trip_id` (`trip_id`),
    KEY `ix_chunks_file_id` (`file_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
