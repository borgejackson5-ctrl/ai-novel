-- ============================================================
-- 注意：本文件有意不带 `USE`：库名只能由命令行给出，避免 `mysql <别的库> < 本文件`
--    被文件里的 USE 劫持、写入 ai_drama。
--    执行方式：mysql -uroot -p ai_drama < migration_add_comment_chapter.sql
-- 章评（本章说）：给评论表增加「章节」维度
--
-- 背景：t_comment 原本只能评论整本书（书评）。加上 chapter_id 后，
-- 同一个模块可以同时承载书评（chapter_id IS NULL）与章评（chapter_id = 某章）。
--
-- 影响面（执行前需核对）：
--   t_comment 新增 1 列 + 1 个索引，不改动既有列，**存量书评自动落在 chapter_id IS NULL 分支**，
--   行为与之前完全一致。
--
-- 说明：MySQL 8.0 的 ADD COLUMN 不支持 IF NOT EXISTS，本脚本只能执行一次；
-- 重跑会报 Duplicate column name。
-- ============================================================

ALTER TABLE `t_comment`
    ADD COLUMN `chapter_id` BIGINT DEFAULT NULL
        COMMENT '章节ID（null=书评，非空=该章的章评）' AFTER `novel_id`,
    ADD KEY `idx_chapter` (`chapter_id`);
