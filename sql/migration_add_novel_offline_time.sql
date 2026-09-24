-- ---------------------------------------------------------------
-- 注意：本文件有意不带 `USE`：库名只能由命令行给出，避免 `mysql <别的库> < 本文件`
--    被文件里的 USE 劫持、写入 ai_drama。
--    执行方式：mysql -uroot -p ai_drama < migration_add_novel_offline_time.sql
-- 作者自助下架 / 重新上架 / 删除：t_novel 增加下架时间
--
-- 用途（两个门槛判定均依赖该列）：
--   1) 下架后 24h 内不可申请重新上架，避免误操作，同时防止以反复上下架刷榜单
--   2) 需已下架满 7 天才能删除，给作者反悔窗口，也给已收藏的读者缓冲
--
-- 不能复用 update_time 的原因：任何一次更新（改简介、调价、审核通过）都会刷新该列，
-- 下架时间点会丢失。
--
-- 非幂等：重复执行会报 `Duplicate column name 'offline_time'`，属预期。
-- ---------------------------------------------------------------

ALTER TABLE `t_novel`
    ADD COLUMN `offline_time` DATETIME DEFAULT NULL COMMENT '下架时间（用于重新上架冷却与删除门槛）' AFTER `audit_result`;
