-- ============================================================
-- 评论系统迁移：新增 t_comment 表
-- 执行方式：mysql -uroot -p ai_drama < migration_add_comment_table.sql
-- （全新库直接跑 init.sql 已含本表，无需本脚本）
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_comment` (
    `id`          BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL COMMENT '评论人用户ID',
    `novel_id`    BIGINT       NOT NULL COMMENT '小说ID',
    `parent_id`   BIGINT       NULL COMMENT '父评论ID(null=顶层评论)',
    `content`     VARCHAR(500) NOT NULL COMMENT '评论内容',
    `like_count`  INT          NOT NULL DEFAULT 0 COMMENT '点赞数',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_novel` (`novel_id`, `parent_id`),
    KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表';
