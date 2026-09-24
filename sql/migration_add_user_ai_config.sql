-- ============================================================
-- 迁移：新增「用户 AI 配置表」（BYOK + 平台免费额度）
-- 背景：t_user_ai_config 已写入 init.sql，但 init.sql 仅在 MySQL
--       数据卷首次初始化时执行；对「已运行的库」需手动执行本脚本。
-- 幂等：可重复执行（IF NOT EXISTS）。
-- 用法（服务器）：
--   docker exec -i ai-novel-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' ai_drama < sql/migration_add_user_ai_config.sql
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_user_ai_config` (
    `id`           BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `own_key`      VARCHAR(255) DEFAULT NULL COMMENT '用户自带 API Key(空=用平台Key)',
    `used_count`   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已消耗平台免费额度',
    `quota_limit`  INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '平台免费额度上限',
    `create_time`  DATETIME     DEFAULT NULL,
    `update_time`  DATETIME     DEFAULT NULL,
    `is_deleted`   TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户AI配置表';
