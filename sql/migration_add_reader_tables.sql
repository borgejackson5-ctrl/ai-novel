-- ============================================================
-- 阅读器增强迁移：新增 t_reading_progress / t_reader_preference 两表
-- 已有库执行方式：mysql -uroot -p ai_drama < migration_add_reader_tables.sql
-- （全新库直接跑 init.sql 即可，无需本脚本）
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_reading_progress` (
    `id`            BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `novel_id`      BIGINT       NOT NULL COMMENT '小说ID',
    `chapter_id`    BIGINT       NOT NULL COMMENT '最后阅读章节ID',
    `novel_title`   VARCHAR(128) NOT NULL COMMENT '书名(冗余，供续读栏展示)',
    `chapter_no`    INT          NOT NULL COMMENT '章序号',
    `chapter_title` VARCHAR(128) NOT NULL COMMENT '章标题(冗余)',
    `mode`          VARCHAR(16)  NOT NULL DEFAULT 'scroll' COMMENT 'scroll滚动/page仿真翻页',
    `scroll_top`    INT          NOT NULL DEFAULT 0 COMMENT '滚动位置(滚动模式)',
    `page_no`       INT          NOT NULL DEFAULT 0 COMMENT '页码(仿真模式)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读进度表';

CREATE TABLE IF NOT EXISTS `t_reader_preference` (
    `id`           BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `font_size`    INT          NOT NULL DEFAULT 18 COMMENT '字号px',
    `font_family`  VARCHAR(16)  NOT NULL DEFAULT 'song' COMMENT 'song宋/hei黑/kai楷',
    `line_height`  DECIMAL(4,2) NOT NULL DEFAULT 2.00 COMMENT '行距',
    `column_width` INT          NOT NULL DEFAULT 680 COMMENT '栏宽px',
    `theme`        VARCHAR(16)  NOT NULL DEFAULT 'day' COMMENT 'day白天/sepia护眼/night夜间',
    `brightness`   DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '亮度0.3~1',
    `mode`         VARCHAR(16)  NOT NULL DEFAULT 'scroll' COMMENT 'scroll/page',
    `auto_speed`   INT          NOT NULL DEFAULT 60 COMMENT '自动阅读速度px/秒',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读偏好表';
