-- 充值订单：新增支付截止时间，配合延迟队列实现超时自动关单
-- 注意：本文件有意不带 `USE`：库名只能由命令行给出，避免 `mysql <别的库> < 本文件`
--    被文件里的 USE 劫持、写入 ai_drama。
--    执行方式：mysql -uroot -p ai_drama < migration_add_recharge_expire.sql
ALTER TABLE `t_recharge_order`
    ADD COLUMN `expire_time` DATETIME DEFAULT NULL COMMENT '支付截止时间（超时自动关单）' AFTER `status`;
