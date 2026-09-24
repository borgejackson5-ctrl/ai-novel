-- ============================================================
-- 用户反馈模块迁移：新增 t_feedback 表
-- 执行方式：mysql -uroot -p ai_drama < migration_add_feedback_table.sql
-- （全新库直接跑 init.sql 已含本表，无需本脚本）
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_feedback` (
    `id`          BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL COMMENT '反馈人用户ID（匿名仅隐藏展示，仍落库用于奖励定位/防刷）',
    `type`        VARCHAR(20)  NOT NULL COMMENT '反馈类型：FEELING/SUGGESTION/BUG',
    `content`     VARCHAR(1000) NOT NULL COMMENT '反馈内容',
    `contact`     VARCHAR(100) NULL COMMENT '选填联系方式',
    `anonymous`   TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否匿名（0实名/1匿名）',
    `status`      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '处理状态（0待处理/1已采纳/2未采纳）',
    `reward_coin` INT          NOT NULL DEFAULT 0 COMMENT '奖励虚拟币数',
    `reply`       VARCHAR(500) NULL COMMENT '管理员回复',
    `reply_time`  DATETIME     NULL COMMENT '回复时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户反馈表';
