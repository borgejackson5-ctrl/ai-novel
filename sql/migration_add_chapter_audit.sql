-- ============================================================
-- 章节级审核迁移：t_chapter 增加审核状态 + 影子正文
-- 执行方式：mysql -uroot -p ai_drama < migration_add_chapter_audit.sql
-- （全新库直接跑 init.sql 已含本三列，无需本脚本）
--
-- audit_status 语义：0 待审(新章) / 1 已通过 / 2 已拒绝 / 3 变更待审(已发布章有修改在审)
-- 历史数据默认 1（通过），避免存量已发布内容因加字段被过滤。
-- ============================================================

ALTER TABLE `t_chapter`
  ADD COLUMN `audit_status`    TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '审核状态 0待审 1通过 2拒绝 3变更待审' AFTER `unlock_coin`,
  ADD COLUMN `audit_result`    VARCHAR(500) DEFAULT NULL COMMENT '审核意见/拒绝原因' AFTER `audit_status`,
  ADD COLUMN `pending_content` MEDIUMTEXT DEFAULT NULL COMMENT '待审正文(影子正文,通过后覆盖 content)' AFTER `audit_result`,
  ADD KEY `idx_novel_audit` (`novel_id`, `audit_status`);
