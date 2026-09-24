-- ----------------------------
-- 注意：本文件有意不带 `USE`：库名只能由命令行给出，避免 `mysql <别的库> < 本文件`
--    被文件里的 USE 劫持、写入 ai_drama。
--    执行方式：mysql -uroot -p ai_drama < migration_user_ai_config_byok.sql
-- 用户 AI 配置：补「自带 Key 的 endpoint / 模型」
--
-- 背景：原先用户只能填写一个 Key，服务地址与模型固定使用平台的。
-- 后果是用户填入第三方 Key 必然失败：请求被发往平台配置的服务，返回 401，
-- 用户看到的是「验证失败」，而非明确的「不支持该服务」。
--
-- 增加 base_url / model 两列后，用户自带 Key 可指向任意 OpenAI 兼容服务
-- （DeepSeek / 通义千问 / Kimi / 智谱 / 本地 Ollama…）。
-- 两列均可空：留空即沿用平台配置，老用户行为不变。
--
-- 注意：ALTER 不幂等，重跑会报 Duplicate column，属预期。
-- 本脚本与 sql/init.sql 保持一致。
-- ----------------------------

ALTER TABLE `t_user_ai_config`
    ADD COLUMN `base_url` VARCHAR(255) DEFAULT NULL COMMENT '自带Key的服务地址(空=沿用平台)' AFTER `own_key`,
    ADD COLUMN `model`    VARCHAR(64)  DEFAULT NULL COMMENT '自带Key的模型名(空=沿用平台)'  AFTER `base_url`;
