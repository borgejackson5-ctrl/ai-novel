-- ============================================================
-- 迁移：作品信息变更送审（影子字段）+ 按发布者查询索引
-- 背景：此前用户发布作品后，书名/简介/封面/标签/分类/笔名全部锁定，仅管理员的
--       /novel/save 可修改。开放作者自助编辑必须先解决「先发合规内容过审、再改为违规
--       内容绕过审核」的漏洞，因此沿用项目已有的「章节影子正文」做法：
--       改动写入 pending_* 影子字段，进入变更待审；审核期间前台继续显示旧值，
--       通过后整体覆盖、拒绝则丢弃影子值并保留旧值。
-- 幂等：本脚本非幂等（MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS），
--       重复执行会报 duplicate column，属预期；已升级过的库无需重复执行。
-- 用法（服务器）：
--   docker exec -i ai-novel-mysql mysql -uroot -p'<MYSQL_ROOT_PASSWORD>' ai_drama < sql/migration_add_novel_pending_edit.sql
-- ============================================================
ALTER TABLE `t_novel`
    ADD COLUMN `pending_title`       VARCHAR(100)  NULL COMMENT '待审书名（变更待审时的影子值）',
    ADD COLUMN `pending_intro`       VARCHAR(1000) NULL COMMENT '待审简介',
    ADD COLUMN `pending_cover_url`   VARCHAR(255)  NULL COMMENT '待审封面URL',
    ADD COLUMN `pending_tags`        VARCHAR(200)  NULL COMMENT '待审标签',
    ADD COLUMN `pending_category_id` BIGINT        NULL COMMENT '待审分类ID',
    ADD COLUMN `pending_author`      VARCHAR(50)   NULL COMMENT '待审笔名',
    MODIFY COLUMN `audit_status` TINYINT UNSIGNED NOT NULL DEFAULT 1
        COMMENT '审核状态 0待审 1通过 2拒绝 3变更待审',
    ADD KEY `idx_user_id` (`user_id`);
