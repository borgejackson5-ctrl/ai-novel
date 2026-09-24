-- ============================================================
-- 作品级「设定事实」表：记录「名词 = 数量/尺寸/时长」这类可核对的数值
--
-- 解决的问题：跨章数字矛盾（前文写「七根伞骨」、本章写「九根」）原先完全依赖模型
-- 主动读取前文。引入名词表后模型调用工具的频次进一步下降（工具调用从 16/12 次降至 0/0），
-- 这类核对整体失效。因此与「写法」相同，将数值也累积成表，由服务端比对。
--
-- 不与 t_novel_glossary 合并为一张表的原因：
--   该表的唯一键是 (novel_id, name)，一个名字一行；而数值的键是 (名词, 数值)，
--   同一个名词可能被记录两个值（这正是需要报出的矛盾），合并入同一张表需修改唯一键，
--   而含 NULL 列的唯一索引在 MySQL 中不约束重复（与「整本解锁」为同一形态）。
--
-- 不带 is_deleted 的原因：同 t_novel_glossary，均为派生数据，带上反而与
-- 「唯一索引不认逻辑删除」冲突（本项目在 t_ai_review_chapter 上曾出现）。
--
-- 建表时未包含 source 列的老库，还需再执行一次 migration_add_novel_fact_source.sql
-- 补上 source 列；该文件幂等，新库上执行只是跳过，不会报重复列。
-- 生产执行：mysql --default-character-set=utf8mb4 -uroot ai_drama < 本文件
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_novel_fact` (
    `id`               BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    `novel_id`         BIGINT       NOT NULL COMMENT '作品ID',
    `name`             VARCHAR(32)  NOT NULL COMMENT '被计量的东西（刀/伞骨/年龄），2~6 个字',
    `fact_value`       VARCHAR(32)  NOT NULL COMMENT '正文里原样出现的数值（含量词，如「七根」「两尺」）',
    `source`           VARCHAR(16)  NOT NULL DEFAULT 'model' COMMENT '数值来源：model=模型报的，scan=服务端从正文里扫出来的',
    `first_chapter_id` BIGINT       NULL COMMENT '首次出现的章节ID',
    `first_chapter_no` INT          NULL COMMENT '首次出现的章号（给作者的依据就靠它）',
    `hit_count`        INT          NOT NULL DEFAULT 1 COMMENT '被审查到的次数（越高说明越稳）',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_novel_fact` (`novel_id`, `name`, `fact_value`) COMMENT '同一本书里同一个数值只留一行',
    KEY `idx_novel` (`novel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作品级设定事实表（跨章数字核对依据）';
