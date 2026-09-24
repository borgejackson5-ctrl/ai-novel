-- ============================================================
-- 删除 AI 生成记录表 t_ai_generation_log
--
-- 删除原因：
--   ① 覆盖不全：6 条 AI 链路中只有「同步起名/简介」与「智能搜索」写入本表，
--      续写、润色、流式起名、章节审查、全文审查均不写入，与「AI 调用审计」不符；
--   ② 口径冲突：本表将 prompt（用户输入）与 result（模型输出）原文写入数据库，
--      而项目对调用记录的口径是「只记模型 / 耗时 / 字数，不记正文」
--      （见 AiClient.logCall 与 AiProperties.enableLog 的注释）；
--   ③ 字段为遗留：status（0处理中/1成功/2失败）与 result_url（生成产物地址）
--      是文生图任务表的列，其注释已声明「文本生成不使用」；
--   ④ 无读取方：Mapper 只有 insert，没有查询接口，前端也没有页面。
--
-- 调用量与降级率现取自应用日志：AiClient.logCall 输出「scene/model/耗时/字数」，
-- 智能搜索再输出一行「model=… degraded=true/false 耗时=… queryChars=…」，
-- 用 grep 即可计算降级率，且日志中不含用户检索词。
--
-- 注意：执行前需确认已完成备份。
--    备份内容：91 行 / 2 个用户 / result+prompt 正文合计约 14.5K 字符。
--    恢复：mysql --default-character-set=utf8mb4 -uroot ai_drama < 备份文件
--
-- 与同目录其它 migration 一致：不带 USE，执行时必须显式指定库。
--    含中文的库需加 --default-character-set=utf8mb4。
--
-- 本脚本幂等：DROP TABLE IF EXISTS 重复执行不报错。
--    因此此处不写入「先 SELECT COUNT(*) FROM t_ai_generation_log 查看」语句：
--    表不存在时该 SELECT 会直接报 1146，第二次执行即失败。
--    行数记录在上面的注释中（执行前也可自行 SELECT 查看，该操作为只读）。
-- ============================================================

DROP TABLE IF EXISTS `t_ai_generation_log`;

-- 末尾输出一行结果，以区分「跳过」与「未生效」
SELECT IF(COUNT(*) = 0, 'OK：t_ai_generation_log 已不存在（删除成功或此前已删）',
                          '⚠️ 表仍在，删除没生效，请检查权限')
       AS result
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_ai_generation_log';
