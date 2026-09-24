-- ============================================================
-- 书架 + 阅读历史迁移：新增 t_bookshelf / t_read_history 两表
-- 执行方式：mysql -uroot -p ai_drama < migration_add_bookshelf_history.sql
-- （全新库直接跑 init.sql 已含两表，无需本脚本）
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_bookshelf` (
    `id`          BIGINT NOT NULL COMMENT '主键ID',
    `user_id`     BIGINT NOT NULL COMMENT '用户ID',
    `novel_id`    BIGINT NOT NULL COMMENT '小说ID',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_novel` (`user_id`, `novel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='书架表';

CREATE TABLE IF NOT EXISTS `t_read_history` (
    `id`            BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `novel_id`      BIGINT       NOT NULL COMMENT '小说ID',
    `chapter_id`    BIGINT       NOT NULL COMMENT '章节ID',
    `chapter_no`    INT          NOT NULL COMMENT '章序号',
    `novel_title`   VARCHAR(128) NOT NULL COMMENT '书名(冗余)',
    `chapter_title` VARCHAR(128) NOT NULL COMMENT '章标题(冗余)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读历史表';
