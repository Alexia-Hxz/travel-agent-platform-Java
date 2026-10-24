-- 旅迹 AI：初始表结构（与 Python 版 SQLAlchemy 模型逐列对齐）
-- 说明：
--   1. SQLAlchemy 的 Float 在 SQLite 上是 8 字节 REAL；MySQL 的 FLOAT 只有 4 字节，
--      会把经纬度精度截断，因此这里统一用 DOUBLE。
--   2. 所有时间列统一存 UTC（容器与连接串都设为 UTC），与 Python 侧 datetime.utcnow() 一致。
--   3. 六个字符串枚举列都不加 CHECK 约束：与 SQLAlchemy 行为一致（校验在应用层）。

CREATE TABLE `users` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `username`           VARCHAR(40)  NULL,
    `email`              VARCHAR(160) NULL,
    `phone`              VARCHAR(24)  NULL,
    `password_hash`      VARCHAR(255) NOT NULL,
    `display_name`       VARCHAR(60)  NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `last_login_at`      DATETIME     NULL,
    `email_verified_at`  DATETIME     NULL,
    `phone_verified_at`  DATETIME     NULL,
    `failed_login_count` INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`),
    UNIQUE KEY `uk_users_email` (`email`),
    UNIQUE KEY `uk_users_phone` (`phone`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `trips` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`            BIGINT       NULL,
    `title`              VARCHAR(120) NOT NULL,
    `destination`        VARCHAR(120) NOT NULL,
    `start_date`         DATE         NOT NULL,
    `end_date`           DATE         NOT NULL,
    `budget`             INT          NULL,
    `adult_male_count`   INT          NOT NULL DEFAULT 0,
    `adult_female_count` INT          NOT NULL DEFAULT 0,
    `child_male_count`   INT          NOT NULL DEFAULT 0,
    `child_female_count` INT          NOT NULL DEFAULT 0,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `ix_trips_user_id` (`user_id`),
    KEY `ix_trips_destination` (`destination`),
    CONSTRAINT `fk_trips_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `places` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `trip_id`        BIGINT       NOT NULL,
    `name`           VARCHAR(160) NOT NULL,
    `type`           VARCHAR(20)  NOT NULL,
    `address`        VARCHAR(300) NOT NULL,
    `latitude`       DOUBLE       NULL,
    `longitude`      DOUBLE       NULL,
    `image_url`      VARCHAR(500) NULL,
    `rating`         DOUBLE       NULL,
    `price_level`    INT          NULL,
    `opening_hours`  VARCHAR(300) NULL,
    `source_url`     VARCHAR(500) NULL,
    `note`           VARCHAR(600) NULL,
    `estimated_cost` INT          NULL,
    `quantity`       INT          NULL,
    `preferred_date` DATE         NULL,
    PRIMARY KEY (`id`),
    KEY `ix_places_trip_id` (`trip_id`),
    CONSTRAINT `fk_places_trip` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `itinerary_items` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `trip_id`    BIGINT       NOT NULL,
    `date`       DATE         NOT NULL,
    `start_time` TIME         NOT NULL,
    `end_time`   TIME         NOT NULL,
    `place_id`   BIGINT       NOT NULL,
    `note`       VARCHAR(500) NULL,
    `sort_order` INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `ix_itinerary_items_trip_id` (`trip_id`),
    KEY `ix_itinerary_items_date` (`date`),
    KEY `ix_itinerary_items_place_id` (`place_id`),
    CONSTRAINT `fk_itinerary_items_trip` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_itinerary_items_place` FOREIGN KEY (`place_id`) REFERENCES `places` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `itinerary_adjustments` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `trip_id`       BIGINT       NOT NULL,
    `instruction`   VARCHAR(500) NOT NULL,
    `proposal_json` JSON         NOT NULL,
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'pending',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `applied_at`    DATETIME     NULL,
    PRIMARY KEY (`id`),
    KEY `ix_itinerary_adjustments_trip_id` (`trip_id`),
    KEY `ix_itinerary_adjustments_status` (`status`),
    CONSTRAINT `fk_itinerary_adjustments_trip` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE `trip_files` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `trip_id`           BIGINT       NOT NULL,
    `original_filename` VARCHAR(255) NOT NULL,
    `stored_filename`   VARCHAR(255) NOT NULL,
    `content_type`      VARCHAR(100) NOT NULL,
    `file_size`         INT          NOT NULL,
    `file_path`         VARCHAR(500) NOT NULL,
    `parse_status`      VARCHAR(20)  NOT NULL DEFAULT 'pending',
    `parsed_text`       TEXT         NULL,
    `parsed_result`     JSON         NULL,
    `parse_error`       TEXT         NULL,
    `parsed_at`         DATETIME     NULL,
    `index_status`      VARCHAR(20)  NOT NULL DEFAULT 'pending',
    `index_error`       TEXT         NULL,
    `indexed_chunks`    INT          NOT NULL DEFAULT 0,
    `indexed_at`        DATETIME     NULL,
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_trip_files_stored_filename` (`stored_filename`),
    KEY `ix_trip_files_trip_id` (`trip_id`),
    CONSTRAINT `fk_trip_files_trip` FOREIGN KEY (`trip_id`) REFERENCES `trips` (`id`) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
