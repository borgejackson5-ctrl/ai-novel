-- ============================================================
-- 登录链路改造迁移：t_user 增加 email（唯一）/ phone（预留）
-- 已有库执行方式：mysql -uroot -p ai_drama < migration_add_email_phone.sql
-- （全新库直接跑 init.sql 即可，无需本脚本）
-- ============================================================

ALTER TABLE `t_user`
    ADD COLUMN `email` VARCHAR(100) DEFAULT NULL COMMENT '邮箱(登录方式之一)' AFTER `avatar`,
    ADD COLUMN `phone` VARCHAR(20)  DEFAULT NULL COMMENT '手机号(预留,暂不参与登录)' AFTER `email`,
    ADD UNIQUE KEY `uk_email` (`email`);
