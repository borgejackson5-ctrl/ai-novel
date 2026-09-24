-- ============================================================
-- t_novel_fact 增加 source 列：记录数值来源
--
-- 背景：原实现数值只有一条来源，即模型在审查时一并上报（`facts` 字段）。
-- 该来源覆盖率偏低：8 章仅累积 2~3 条，dev 环境「刀 三尺 / 两尺」即因
-- 第 1 章从未上报「刀=三尺」而遗漏。现增加第二条来源：
-- 服务端在正文中扫描「名词 + 紧邻的数量短语」（见 NovelGlossaryServiceImpl#scanFacts）。
--
-- 两条来源的可信度不同（模型上报的经过三道校验；扫描的还额外受量词白名单约束，
-- 排除「天/年/月/日」这类时间词），排查问题需分别观察，因此记录来源。
--
-- 默认 'model'：已有数据均为模型上报。
--
-- 本脚本幂等，重复执行不报错（同目录其他 ADD COLUMN 迁移均为一次性）。
--   原因：`source` 列同时写在 init.sql 与 migration_add_novel_fact.sql 的
--   CREATE TABLE 中（供新库直接建出）。因此对「表为新建」的环境再执行本 ALTER，
--   必然触发 ERROR 1060 Duplicate column name 'source'；两个文件同版本发布，
--   执行先后不应报错，故先用 information_schema 查询后再决定。
--   （MySQL 8.0 的 ADD COLUMN 不支持 IF NOT EXISTS，只能采用此方式。）
--
-- 前提：表已存在。新库需先执行 init.sql 或 migration_add_novel_fact.sql。
-- 生产执行：mysql --default-character-set=utf8mb4 -uroot ai_drama < 本文件
-- ============================================================

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 't_novel_fact'
      AND COLUMN_NAME  = 'source'
);

-- 列已存在时改用空操作（DO 0 是合法的可 PREPARE 语句），不执行 ALTER
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE `t_novel_fact`
        ADD COLUMN `source` VARCHAR(16) NOT NULL DEFAULT ''model''
            COMMENT ''数值来源：model=模型报的，scan=服务端从正文里扫出来的''
            AFTER `fact_value`',
    'DO 0');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT IF(@col_exists = 0,
          '已新增 t_novel_fact.source（历史行都记为 model）',
          't_novel_fact.source 已存在，本次跳过（未改动任何数据）') AS result;
