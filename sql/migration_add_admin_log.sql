-- ============================================================
-- 迁移：新增管理端操作日志表 t_admin_log（审计追溯）
-- 背景：管理端的审核、上下架、禁用用户、处理反馈等写操作此前不留痕，
--       出问题无法回答「谁在什么时候对哪条内容做了什么」。
-- 记录方式：后端 @AdminLogRecord 注解 + AdminLogAspect 环绕，成功与失败均写入数据库。
-- 幂等：可重复执行（CREATE TABLE IF NOT EXISTS）。
-- 用法（服务器）：
--   docker exec -i ai-novel-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' ai_drama < sql/migration_add_admin_log.sql
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_admin_log` (
    `id`          BIGINT         NOT NULL COMMENT '主键ID',
    `admin_id`    BIGINT         NULL COMMENT '操作人用户ID',
    `admin_name`  VARCHAR(64)    NULL COMMENT '操作人账号（写入时快照，避免改名/删号后无法追溯）',
    `module`      VARCHAR(32)    NOT NULL COMMENT '模块：AUDIT/USER/ORDER/FEEDBACK/NOVEL/AI/IMPORT',
    `action`      VARCHAR(32)    NOT NULL COMMENT '动作：PASS/REJECT/IMPORT/STATUS/HANDLE/RESET/SAVE/DELETE',
    `target_type` VARCHAR(32)    NULL COMMENT '目标类型：NOVEL/CHAPTER/USER/FEEDBACK/AI_QUOTA',
    `target_id`   BIGINT         NULL COMMENT '目标主键',
    `summary`     VARCHAR(255)   NOT NULL COMMENT '操作摘要（人可读，列表直接展示）',
    `detail`      VARCHAR(1000)  NULL COMMENT '补充详情（拒绝理由/导入书名/变更值）',
    `ip`          VARCHAR(64)    NULL COMMENT '操作来源IP',
    `success`     TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否成功（0失败/1成功）',
    `error_msg`   VARCHAR(500)   NULL COMMENT '失败原因',
    `cost_ms`     BIGINT         NULL COMMENT '耗时毫秒',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_admin_time` (`admin_id`, `create_time`),
    KEY `idx_module_time` (`module`, `create_time`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理端操作日志（审计追溯）';
