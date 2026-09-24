-- ============================================================
-- 全文审查：任务表 + 每章结果表 + 问题明细表
--
-- 采用三张表而非单张 JSON 大字段的原因：
--   ① 幂等由约束保证而非由代码保证：队列重投时「本章是否审过」
--      由 uk_task_chapter 唯一索引回答，无需在内存中记账（进程重启即丢失）；
--   ② 进度为一次 COUNT 或累加，无需读出整本书的 JSON 再解析；
--   ③ 「审过且无问题」的章在结果表中有行、在问题表无行，两者可直接区分
--      （若只有问题表，「未审成」与「审过无问题」会混为同一种「没有记录」）。
--
-- 幂等：IF NOT EXISTS，可重复执行。MySQL 8.0 不支持 DROP COLUMN IF EXISTS，
--       故不做破坏性改写，回滚语句写在文件末尾、需要时手工执行。
-- ============================================================

-- ----------------------------
-- 全文审查任务
-- ----------------------------
CREATE TABLE IF NOT EXISTS `t_ai_review_task` (
    `id`               BIGINT         NOT NULL COMMENT '主键ID',
    `user_id`          BIGINT         NOT NULL COMMENT '发起人用户ID',
    `novel_id`         BIGINT         NOT NULL COMMENT '作品ID',
    `novel_title`      VARCHAR(128)   DEFAULT NULL COMMENT '书名快照（任务列表展示，作品改名不影响历史任务）',
    `status`           TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0排队中 1审查中 2已完成 3已中止 4失败',
    `total_chapters`   INT            NOT NULL DEFAULT 0 COMMENT '本次要审的章节数',
    `done_chapters`    INT            NOT NULL DEFAULT 0 COMMENT '已处理完的章节数（含审失败的）',
    `failed_chapters`  INT            NOT NULL DEFAULT 0 COMMENT '其中审失败的章节数',
    `issue_count`      INT            NOT NULL DEFAULT 0 COMMENT '发现的问题条数',
    `charged_units`    INT            NOT NULL DEFAULT 0 COMMENT '本次累计扣掉的免费字数',
    `refunded_units`   INT            NOT NULL DEFAULT 0 COMMENT '失败章节已退回的字数',
    `reviewed_chars`   INT            NOT NULL DEFAULT 0 COMMENT '累计审查的正文字数',
    `message`          VARCHAR(255)   DEFAULT NULL COMMENT '给作者看的说明（中止/失败原因），不出现实现细节',
    `finish_time`      DATETIME       DEFAULT NULL COMMENT '结束时间（完成/中止/失败时写入）',
    `create_time`      DATETIME       DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`       TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `create_time`),
    KEY `idx_novel_status` (`novel_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 全文审查任务';

-- ----------------------------
-- 全文审查：每章的处理结果（幂等判据 + 进度明细）
--
-- 「这一章为什么没有结果」必须可回答：status=2 时 message 写明原因。
-- 若只在问题表里记录，整个 task 结果为空时无法区分「全部审过、无问题」与「全部未审成」。
-- ----------------------------
CREATE TABLE IF NOT EXISTS `t_ai_review_chapter` (
    `id`             BIGINT         NOT NULL COMMENT '主键ID',
    `task_id`        BIGINT         NOT NULL COMMENT '所属任务ID',
    `novel_id`       BIGINT         NOT NULL COMMENT '作品ID',
    `chapter_id`     BIGINT         NOT NULL COMMENT '章节ID',
    `chapter_no`     INT            NOT NULL COMMENT '章号',
    `chapter_title`  VARCHAR(128)   DEFAULT NULL COMMENT '章标题快照',
    `status`         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0审查中 1已审完 2审查失败',
    `segments`       INT            NOT NULL DEFAULT 1 COMMENT '本章切成了几段送审',
    `reviewed_chars` INT            NOT NULL DEFAULT 0 COMMENT '本章送审的正文字数',
    `issue_count`    INT            NOT NULL DEFAULT 0 COMMENT '本章发现的问题条数',
    `dropped_issues` INT            NOT NULL DEFAULT 0 COMMENT '本章因核对不上被丢弃的条数（反幻觉指标）',
    `summary`        VARCHAR(512)   DEFAULT NULL COMMENT '本章一句话总评',
    `message`        VARCHAR(255)   DEFAULT NULL COMMENT 'status=2 时的原因（给作者看）',
    `create_time`    DATETIME       DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME       DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`     TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    -- 幂等判据：队列重投与同一任务重复派发均由此唯一索引拦截
    UNIQUE KEY `uk_task_chapter` (`task_id`, `chapter_id`),
    KEY `idx_task_no` (`task_id`, `chapter_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 全文审查：每章结果';

-- ----------------------------
-- 全文审查：问题明细（可点到具体章节与段落）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `t_ai_review_issue` (
    `id`            BIGINT       NOT NULL COMMENT '主键ID',
    `task_id`       BIGINT       NOT NULL COMMENT '所属任务ID',
    `chapter_id`    BIGINT       NOT NULL COMMENT '章节ID',
    `chapter_no`    INT          NOT NULL COMMENT '章号（列表按它排，作者能直接翻过去）',
    `chapter_title` VARCHAR(128) DEFAULT NULL COMMENT '章标题快照',
    `segment_no`    INT          NOT NULL DEFAULT 1 COMMENT '问题落在本章第几段（长章切段后 >1）',
    `type`          VARCHAR(16)  DEFAULT NULL COMMENT '错别字/语病/标点/前后不一致',
    `excerpt`       VARCHAR(255) DEFAULT NULL COMMENT '正文里的原样片段',
    `suggestion`    VARCHAR(512) DEFAULT NULL COMMENT '修改建议',
    `dedup_key`     VARCHAR(128) DEFAULT NULL COMMENT '归一化去重键（类型+片段），跨章归并时用',
    `create_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`    TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_task_chapter` (`task_id`, `chapter_id`),
    KEY `idx_task_dedup` (`task_id`, `dedup_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 全文审查：问题明细';

-- ============================================================
-- 回滚（需要时手工执行；本环境无外键，删表不会牵连别的表）：
--   DROP TABLE IF EXISTS `t_ai_review_issue`;
--   DROP TABLE IF EXISTS `t_ai_review_chapter`;
--   DROP TABLE IF EXISTS `t_ai_review_task`;
-- ============================================================
