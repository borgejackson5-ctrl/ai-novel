-- ----------------------------
-- 注意：本文件有意不带 `USE`：库名只能由命令行给出，避免 `mysql <别的库> < 本文件`
--    被文件里的 USE 劫持、写入 ai_drama。
--    执行方式：mysql -uroot -p ai_drama < migration_unique_subscribe_order.sql
-- 订阅（解锁）订单：补唯一索引，为并发防重提供数据库级兜底
--
-- 背景：解锁入口使用 Redisson 分布式锁 + 锁内双检防止重复下单，但该保护仅在应用层。
-- 一旦锁失效（Redis 抖动、多实例锁配置不一致、绕过 service 直接写库），
-- 同一用户对同一章 / 同一本会产生重复订单，导致重复扣币且无自动告警。
--
-- 约束：chapter_id 可为 NULL（整本解锁），而 MySQL 唯一索引不约束 NULL，多行 NULL 可以共存，
-- 因此不能直接对 (user_id, novel_id, chapter_id) 建唯一索引。
-- 实现方式为增加一个生成列将 NULL 归一化为 0，再对生成列建唯一索引，整本解锁因此同样可被去重。
--
-- 注意：执行前需先执行「第 1 步」检测：若已存在重复数据，第 2 步会失败，需先人工核对处理。
-- 本脚本与 sql/init.sql 保持结构一致。ALTER 不幂等，重跑会报 Duplicate column/key，属预期。
-- ----------------------------

-- 第 1 步（只读检测）：正常情况下应返回 0 行。
-- 若有输出，说明已存在重复解锁订单，需先确认并清理，再执行第 2 步。
SELECT user_id, novel_id, IFNULL(chapter_id, 0) AS chapter_key, COUNT(*) AS dup_count
FROM t_subscribe_order
GROUP BY user_id, novel_id, chapter_key
HAVING COUNT(*) > 1;

-- 第 2 步：生成列 + 唯一索引
ALTER TABLE `t_subscribe_order`
    ADD COLUMN `chapter_key` BIGINT GENERATED ALWAYS AS (IFNULL(`chapter_id`, 0)) STORED
        COMMENT '整本解锁归一化(NULL→0)，供唯一索引去重',
    ADD UNIQUE KEY `uk_user_novel_chapter` (`user_id`, `novel_id`, `chapter_key`);
