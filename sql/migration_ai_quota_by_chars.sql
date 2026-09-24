-- AI 免费额度：从「按次」改成「按字数」
--
-- 背景：额度口径改成「未自带 Key 的用户每天 3 万字」（≈ 10 章正文），
--       `quota_limit` 列的含义从「每天几次」变成「每天多少字」。
--       代码里的默认值已随之改为 30000（AiConfigServiceImpl.DEFAULT_QUOTA），
--       这里把存量数据与列默认值一起对齐；只改代码不改库时，
--       老用户会带着「1 字/天」的额度继续运行。
--
-- 幂等：数据更新带原值条件（quota_limit = 1），重跑影响 0 行；
--       ALTER 重复执行结果相同。
-- 不带 USE：库名由命令行给出（指定错误的库，其影响大于漏执行）。
--
-- 注意：Redis 里的当日计数（ai:user:usage:{userId}:{yyyy-MM-dd}）不会随之变化：
--    旧值语义是「次」、新值语义是「字」。影响可忽略（旧值很小，等于少扣用户一点额度），
--    跨天自动清零。如需立即归零可 DEL 当天的 key（不可使用 FLUSHALL）。

-- 1) 存量行：旧的「1 次」→「30000 字」
UPDATE t_user_ai_config SET quota_limit = 30000 WHERE quota_limit = 1;

-- 2) 列默认值与注释：口径从「次」变「字」；used_count 已废弃（计数迁到 Redis）
ALTER TABLE t_user_ai_config
    MODIFY COLUMN `quota_limit` INT UNSIGNED NOT NULL DEFAULT 30000 COMMENT '平台免费额度上限(字)',
    MODIFY COLUMN `used_count`  INT UNSIGNED NOT NULL DEFAULT 0     COMMENT '废弃：额度计数已迁到 Redis(ai:user:usage:*)';

-- ============ 回滚（需要时手工执行） ============
-- UPDATE t_user_ai_config SET quota_limit = 1 WHERE quota_limit = 30000;
-- ALTER TABLE t_user_ai_config
--     MODIFY COLUMN `quota_limit` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '平台免费额度上限',
--     MODIFY COLUMN `used_count`  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已消耗平台免费额度';
