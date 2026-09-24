-- ============================================================
-- 阅读进度 LWW 冲突合并迁移：t_reading_progress 加 client_time 列
-- 已有库（已建过 reader 表）执行方式：
--   mysql -uroot -p ai_drama < migration_add_reader_client_time.sql
-- 全新库直接跑 init.sql 已含本列，无需本脚本。
-- ============================================================

ALTER TABLE `t_reading_progress`
    ADD COLUMN `client_time` BIGINT NULL COMMENT '客户端最后写入时间戳(ms，LWW冲突合并)' AFTER `page_no`;
