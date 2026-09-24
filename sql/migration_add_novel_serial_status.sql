-- ============================================================
-- 注意：本文件有意不带 `USE`：库名只能由命令行给出，避免 `mysql <别的库> < 本文件`
--    被文件里的 USE 劫持、写入 ai_drama。
--    执行方式：mysql -uroot -p ai_drama < migration_add_novel_serial_status.sql
-- 作品连载状态（连载中 / 已完结）+ 解除完结申请工单
--
-- 背景：作者可以把自己的作品标记为「已完结」。标记后章节与简介等内容型字段锁定，
-- 只允许改书名和封面；作者反悔要走「申请解除完结」，管理员批准后才能继续更新，
-- 且转为完结后有 3 天冷静期不可申请。
--
-- 影响面（执行前需核对）：
--   t_novel   新增 2 列（serial_status / finish_time），不改动既有列
--   t_novel_appeal  新建表（不影响既有表）
--   UPDATE 会把「系统导入的公版书」（user_id IS NULL）标记为已完结，
--   因为 59 本公版名著本身即为完本，默认成「连载中」不符合事实。
--   作者自建作品保持「连载中」。
--
-- 说明：MySQL 8.0 的 ADD COLUMN 不支持 IF NOT EXISTS，本脚本只能执行一次；
-- 重跑会报 Duplicate column name。
-- ============================================================

ALTER TABLE `t_novel`
    ADD COLUMN `serial_status` TINYINT UNSIGNED NOT NULL DEFAULT 0
        COMMENT '连载状态 0连载中 1已完结' AFTER `audit_result`,
    ADD COLUMN `finish_time` DATETIME DEFAULT NULL
        COMMENT '转为完结的时间（解除完结冷静期的起算点）' AFTER `serial_status`;

-- 历史数据：系统导入的公版书视为完本
UPDATE `t_novel`
SET `serial_status` = 1,
    `finish_time` = NOW()
WHERE `user_id` IS NULL
  AND `serial_status` = 0;

CREATE TABLE IF NOT EXISTS `t_novel_appeal` (
    `id`            BIGINT           NOT NULL COMMENT '主键ID',
    `novel_id`      BIGINT           NOT NULL COMMENT '小说ID',
    `user_id`       BIGINT           NOT NULL COMMENT '申请人用户ID',
    `type`          VARCHAR(20)      NOT NULL COMMENT '申请类型 RESUME_SERIAL=解除完结',
    `reason`        VARCHAR(500)     DEFAULT NULL COMMENT '申请理由',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态 0待处理 1已通过 2已驳回',
    `admin_reply`   VARCHAR(500)     DEFAULT NULL COMMENT '管理员回复',
    `handle_user_id` BIGINT          DEFAULT NULL COMMENT '处理人用户ID',
    `handle_time`   DATETIME         DEFAULT NULL COMMENT '处理时间',
    `create_time`   DATETIME         DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`    TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_novel` (`novel_id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  -- 与全库保持一致：init.sql 与其它表都用 utf8mb4_general_ci。
  -- 写成 utf8mb4_0900_ai_ci 会让这张表与其它表排序规则不同，
  -- 跨表 JOIN / 比较时可能触发隐式转换（无法使用索引、甚至报排序规则冲突）。
  COLLATE = utf8mb4_general_ci COMMENT ='作品申请工单（解除完结等）';
