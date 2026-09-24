-- ============================================================
-- 作品级专有名词表：跨章一致性核对的依据
--
-- 解决的问题：审查一章时，模型能否发现「本章人名与前面几章不一致」，
-- 原先完全取决于其是否调用工具查询前文（同一章两次执行，一次查出、一次遗漏）。
-- 现将「本书已确立的写法」累积成一张表，由服务端核对：
-- 本章出现与表中某个名字「长度相同、仅一处字不同」的写法，即报告「前后不一致」，
-- 不依赖模型行为。
--
-- 不带 is_deleted 的原因（全项目唯一一张不带逻辑删除的业务表）：
--   ① 本表为派生数据，作品删除后即失效，保留孤儿行不影响任何查询；
--   ② 带逻辑删除反而与「唯一索引不认逻辑删除」冲突：
--      被删除的行仍占用 (novel_id, name)，重新累积同一名字时会触发唯一键冲突，
--      且不报错，该名字此后无法写入（本项目在 t_ai_review_chapter 上曾出现）。
-- 生产执行：mysql --default-character-set=utf8mb4 -uroot ai_drama < 本文件
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_novel_glossary` (
    `id`               BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    `novel_id`         BIGINT       NOT NULL COMMENT '作品ID',
    `name`             VARCHAR(32)  NOT NULL COMMENT '专有名词的标准写法（人名/地名/门派/功法/道具）',
    `first_chapter_id` BIGINT       NULL COMMENT '首次出现的章节ID',
    `first_chapter_no` INT          NULL COMMENT '首次出现的章号（给作者的依据就靠它）',
    `hit_count`        INT          NOT NULL DEFAULT 1 COMMENT '被审查到的次数（用来判断这个名字稳不稳）',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_novel_name` (`novel_id`, `name`) COMMENT '同一本书里同一个写法只留一行',
    KEY `idx_novel` (`novel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作品级专有名词表（跨章一致性核对依据）';
