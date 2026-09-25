-- ============================================================
-- 灵阅 AI 小说阅读平台 数据库初始化脚本
-- MySQL 8.0
-- 执行方式: mysql -uroot -p < init.sql
--
-- 注意：本脚本会 DROP 掉 ai_drama 库里的全部业务表。
--   · 头部含 `USE ai_drama`，目标库由脚本内容决定，与命令行参数无关；
--     因此不可用 `mysql <其他库> < init.sql` 的形式执行：
--     USE 会将操作目标切换为 ai_drama。
--   · 仅适用于首次初始化空库：容器首次启动时由 MySQL 官方镜像
--     自动执行（见 docker-compose*.yml 的 docker-entrypoint-initdb.d 挂载）。
--   · 已运行的库要变更结构，应使用 sql/migration_*.sql（这些文件不带 USE）。
--   · 执行前需确认存在可回滚的备份。
--
-- 规约说明（对照《阿里巴巴 Java 开发手册》建表规约）：
--   1. 是否类字段统一 is_xxx + TINYINT UNSIGNED（如 is_deleted）
--   2. 所有非负数值字段均为 UNSIGNED
--   3. 小数字段使用 DECIMAL，禁止 FLOAT/DOUBLE
-- 本脚本为单一 clean 版本，已折叠此前的增量迁移（t_message、投稿人、
-- AI 生成任务状态等），全新库直接执行本脚本即可。
-- 数据库名保持 ai_drama
-- ============================================================

CREATE DATABASE IF NOT EXISTS `ai_drama` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE `ai_drama`;
-- 强制客户端字符集为 utf8mb4，避免中文种子数据被错误解码
SET NAMES utf8mb4;

-- ----------------------------
-- 用户表
-- ----------------------------
DROP TABLE IF EXISTS `t_user`;
CREATE TABLE `t_user` (
    `id`            BIGINT       NOT NULL COMMENT '主键ID',
    `username`      VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password`      VARCHAR(100) NOT NULL COMMENT '密码(BCrypt)',
    `nickname`      VARCHAR(50)  DEFAULT NULL COMMENT '昵称',
    `avatar`        VARCHAR(255) DEFAULT NULL COMMENT '头像',
    `email`         VARCHAR(100) DEFAULT NULL COMMENT '邮箱(登录方式之一)',
    `phone`         VARCHAR(20)  DEFAULT NULL COMMENT '手机号(预留,暂不参与登录)',
    `coin_balance`  INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '虚拟币余额',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 1正常 0禁用',
    `create_time`   DATETIME     DEFAULT NULL COMMENT '创建时间',
    `update_time`   DATETIME     DEFAULT NULL COMMENT '更新时间',
    `is_deleted`    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ----------------------------
-- 角色表
-- ----------------------------
DROP TABLE IF EXISTS `t_role`;
CREATE TABLE `t_role` (
    `id`          BIGINT      NOT NULL COMMENT '主键ID',
    `role_code`   VARCHAR(50) NOT NULL COMMENT '角色编码',
    `role_name`   VARCHAR(50) NOT NULL COMMENT '角色名称',
    `create_time` DATETIME    DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- ----------------------------
-- 用户角色关联表
-- ----------------------------
DROP TABLE IF EXISTS `t_user_role`;
CREATE TABLE `t_user_role` (
    `id`      BIGINT NOT NULL COMMENT '主键ID',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `role_id` BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色关联表';

-- ----------------------------
-- 小说分类表
-- ----------------------------
DROP TABLE IF EXISTS `t_category`;
CREATE TABLE `t_category` (
    `id`           BIGINT      NOT NULL COMMENT '主键ID',
    `name`         VARCHAR(50) NOT NULL COMMENT '分类名称',
    `parent_id`    BIGINT      NOT NULL DEFAULT 0 COMMENT '父分类ID 0为顶级',
    `sort`         INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '排序',
    `status`       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 1启用 0禁用',
    `create_time`  DATETIME    DEFAULT NULL,
    `update_time`  DATETIME    DEFAULT NULL,
    `is_deleted`   TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小说分类表';

-- ----------------------------
-- 小说表
-- ----------------------------
DROP TABLE IF EXISTS `t_novel`;
CREATE TABLE `t_novel` (
    `id`             BIGINT        NOT NULL COMMENT '主键ID',
    `title`          VARCHAR(100)  NOT NULL COMMENT '书名',
    `category_id`    BIGINT        DEFAULT NULL COMMENT '分类ID',
    `cover_url`      VARCHAR(255)  DEFAULT NULL COMMENT '封面URL',
    `intro`          VARCHAR(1000) DEFAULT NULL COMMENT '简介',
    `tags`           VARCHAR(200)  DEFAULT NULL COMMENT '标签(逗号分隔)',
    `author`         VARCHAR(50)   DEFAULT NULL COMMENT '作者',
    `user_id`        BIGINT        DEFAULT NULL COMMENT '发布者用户ID(用户投稿时有值)',
    `total_chapters` INT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '总章节数',
    `word_count`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '总字数',
    `coin_price`     INT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '整本解锁价格(虚拟币)',
    `read_count`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '阅读量',
    `like_count`     BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '点赞量',
    `status`         TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态 1上架 0下架',
    `audit_status`   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '审核状态 0待审 1通过 2拒绝 3变更待审 4重新上架待审',
    `audit_result`   VARCHAR(500)  DEFAULT NULL COMMENT '审核结果/意见',
    `serial_status`  TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '连载状态 0连载中 1已完结',
    `finish_time`    DATETIME      DEFAULT NULL COMMENT '转为完结的时间（解除完结冷静期的起算点）',
    `offline_time`   DATETIME      DEFAULT NULL COMMENT '下架时间（用于重新上架冷却与删除门槛）',
    `pending_title`       VARCHAR(100)  DEFAULT NULL COMMENT '待审书名（变更待审时的影子值）',
    `pending_intro`       VARCHAR(1000) DEFAULT NULL COMMENT '待审简介',
    `pending_cover_url`   VARCHAR(255)  DEFAULT NULL COMMENT '待审封面URL',
    `pending_tags`        VARCHAR(200)  DEFAULT NULL COMMENT '待审标签',
    `pending_category_id` BIGINT        DEFAULT NULL COMMENT '待审分类ID',
    `pending_author`      VARCHAR(50)   DEFAULT NULL COMMENT '待审笔名',
    `create_time`    DATETIME      DEFAULT NULL,
    `update_time`    DATETIME      DEFAULT NULL,
    `is_deleted`     TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_category` (`category_id`),
    KEY `idx_read_count` (`read_count`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='小说表';

-- ----------------------------
-- 作品申请工单表（作者 → 管理员的业务申请，目前用于「解除完结」）
-- ----------------------------
DROP TABLE IF EXISTS `t_novel_appeal`;
CREATE TABLE `t_novel_appeal` (
    `id`             BIGINT           NOT NULL COMMENT '主键ID',
    `novel_id`       BIGINT           NOT NULL COMMENT '小说ID',
    `user_id`        BIGINT           NOT NULL COMMENT '申请人用户ID',
    `type`           VARCHAR(20)      NOT NULL COMMENT '申请类型 RESUME_SERIAL=解除完结',
    `reason`         VARCHAR(500)     DEFAULT NULL COMMENT '申请理由',
    `status`         TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态 0待处理 1已通过 2已驳回',
    `admin_reply`    VARCHAR(500)     DEFAULT NULL COMMENT '管理员回复',
    `handle_user_id` BIGINT           DEFAULT NULL COMMENT '处理人用户ID',
    `handle_time`    DATETIME         DEFAULT NULL COMMENT '处理时间',
    `create_time`    DATETIME         DEFAULT CURRENT_TIMESTAMP,
    `update_time`    DATETIME         DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`     TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_novel` (`novel_id`),
    KEY `idx_user` (`user_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作品申请工单（解除完结等）';

-- ----------------------------
-- 章节表
-- ----------------------------
DROP TABLE IF EXISTS `t_chapter`;
CREATE TABLE `t_chapter` (
    `id`           BIGINT       NOT NULL COMMENT '主键ID',
    `novel_id`     BIGINT       NOT NULL COMMENT '小说ID',
    `chapter_no`   INT UNSIGNED NOT NULL COMMENT '章节序号',
    `title`        VARCHAR(100) DEFAULT NULL COMMENT '章节标题',
    `content`      MEDIUMTEXT   DEFAULT NULL COMMENT '章节正文',
    `word_count`   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本章字数',
    `unlock_coin`  INT UNSIGNED NOT NULL DEFAULT 5 COMMENT '本章解锁所需虚拟币(0为免费章)',
    `audit_status`   TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '审核状态 0待审 1通过 2拒绝 3变更待审',
    `audit_result`   VARCHAR(500)  DEFAULT NULL COMMENT '审核意见/拒绝原因',
    `pending_content` MEDIUMTEXT   DEFAULT NULL COMMENT '待审正文(影子正文,通过后覆盖 content)',
    `sort`         INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '排序',
    `create_time`  DATETIME     DEFAULT NULL,
    `update_time`  DATETIME     DEFAULT NULL,
    `is_deleted`   TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_novel_no` (`novel_id`, `chapter_no`),
    KEY `idx_novel_id` (`novel_id`),
    KEY `idx_novel_audit` (`novel_id`, `audit_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='章节表';

-- ----------------------------
-- 虚拟币流水表
-- ----------------------------
DROP TABLE IF EXISTS `t_coin_log`;
CREATE TABLE `t_coin_log` (
    `id`            BIGINT      NOT NULL COMMENT '主键ID',
    `user_id`       BIGINT      NOT NULL COMMENT '用户ID',
    `change_amount` INT         NOT NULL COMMENT '变动金额(正数增加 负数扣减)',
    `type`          VARCHAR(20) NOT NULL COMMENT '类型 CHARGE充值/UNLOCK解锁/REFUND退款',
    `biz_id`        BIGINT      DEFAULT NULL COMMENT '业务ID(如订单ID)',
    `remark`        VARCHAR(200) DEFAULT NULL COMMENT '备注',
    `create_time`   DATETIME    DEFAULT NULL,
    `update_time`   DATETIME    DEFAULT NULL,
    `is_deleted`    TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='虚拟币流水表';

-- ----------------------------
-- 充值订单表
-- ----------------------------
DROP TABLE IF EXISTS `t_recharge_order`;
CREATE TABLE `t_recharge_order` (
    `id`            BIGINT       NOT NULL COMMENT '主键ID',
    `order_no`      VARCHAR(64)  NOT NULL COMMENT '充值订单号',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `coin_amount`   INT UNSIGNED NOT NULL COMMENT '充值虚拟币数量',
    `pay_amount`    DECIMAL(10,2) DEFAULT NULL COMMENT '支付金额(元)',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消',
    `expire_time`   DATETIME     DEFAULT NULL COMMENT '支付截止时间（超时自动关单）',
    `pay_time`      DATETIME     DEFAULT NULL COMMENT '支付时间',
    `create_time`   DATETIME     DEFAULT NULL,
    `update_time`   DATETIME     DEFAULT NULL,
    `is_deleted`    TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值订单表';

-- ----------------------------
-- 解锁订单表
-- ----------------------------
DROP TABLE IF EXISTS `t_subscribe_order`;
CREATE TABLE `t_subscribe_order` (
    `id`          BIGINT       NOT NULL COMMENT '主键ID',
    `order_no`    VARCHAR(64)  NOT NULL COMMENT '订单号',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `novel_id`    BIGINT       NOT NULL COMMENT '小说ID',
    `chapter_id`  BIGINT       DEFAULT NULL COMMENT '章节ID(整本解锁为NULL)',
    -- 生成列：将「整本解锁」的 chapter_id=NULL 归一化为 0，使唯一索引可覆盖该情形
    -- （MySQL 唯一索引不约束 NULL，直接对 chapter_id 建索引时多行 NULL 可以共存）
    `chapter_key` BIGINT GENERATED ALWAYS AS (IFNULL(`chapter_id`, 0)) STORED
                              COMMENT '整本解锁归一化(NULL→0)，供唯一索引去重',
    `coin_amount` INT UNSIGNED NOT NULL COMMENT '消耗虚拟币',
    `status`      TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0待支付 1已支付 2已取消',
    `create_time` DATETIME     DEFAULT NULL,
    `update_time` DATETIME     DEFAULT NULL,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    UNIQUE KEY `uk_user_novel_chapter` (`user_id`, `novel_id`, `chapter_key`),
    KEY `idx_user_novel` (`user_id`, `novel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='解锁订单表';

-- ----------------------------
-- AI 生成记录表（已删除，见 sql/migration_drop_ai_generation_log.sql）
-- 该表只覆盖 6 条 AI 链路中的 2 条，且将用户输入与模型输出原文写入数据库，
-- 与项目对调用记录的口径冲突；调用量与降级率以应用日志为准。
-- ----------------------------

-- ----------------------------
-- AI 配置表（单行配置，id=1，Key 由界面动态保存）
-- ----------------------------
DROP TABLE IF EXISTS `t_ai_config`;
CREATE TABLE `t_ai_config` (
    `id`            BIGINT       NOT NULL COMMENT '主键ID(固定1)',
    `base_url`      VARCHAR(255) DEFAULT NULL COMMENT '接口地址',
    `api_key`       VARCHAR(255) DEFAULT NULL COMMENT 'API Key',
    `model`         VARCHAR(50)  DEFAULT NULL COMMENT '模型名称',
    `temperature`   DECIMAL(3,2) DEFAULT 0.80 COMMENT '温度',
    `mock_enabled`  TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '无Key时是否mock降级',
    `create_time`   DATETIME     DEFAULT NULL,
    `update_time`   DATETIME     DEFAULT NULL,
    `is_deleted`    TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI配置表';

-- ----------------------------
-- 用户 AI 配置表（每用户一行：自带 Key + 平台免费额度）
-- ----------------------------
DROP TABLE IF EXISTS `t_user_ai_config`;
CREATE TABLE `t_user_ai_config` (
    `id`           BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `own_key`      VARCHAR(255) DEFAULT NULL COMMENT '用户自带 API Key(空=用平台Key)',
    -- 自带 Key 配套的 endpoint / 模型：仅更换 Key 而不更换地址时，第三方 Key 请求平台服务会返回 401
    `base_url`     VARCHAR(255) DEFAULT NULL COMMENT '自带Key的服务地址(空=沿用平台)',
    `model`        VARCHAR(64)  DEFAULT NULL COMMENT '自带Key的模型名(空=沿用平台)',
    `used_count`   INT UNSIGNED NOT NULL DEFAULT 0     COMMENT '废弃：额度计数已迁到 Redis(ai:user:usage:*)',
    `quota_limit`  INT UNSIGNED NOT NULL DEFAULT 30000 COMMENT '平台免费额度上限(字)',
    `create_time`  DATETIME     DEFAULT NULL,
    `update_time`  DATETIME     DEFAULT NULL,
    `is_deleted`   TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户AI配置表';

-- ----------------------------
-- 站内信表
-- ----------------------------
DROP TABLE IF EXISTS `t_message`;
CREATE TABLE `t_message` (
    `id`          BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL COMMENT '接收者用户ID',
    `type`        VARCHAR(32)  NOT NULL COMMENT '类型：AUDIT_SUBMIT/AUDIT_PASS/AUDIT_REJECT',
    `title`       VARCHAR(128) NOT NULL COMMENT '标题',
    `content`     VARCHAR(512) NOT NULL COMMENT '内容',
    `related_id`  BIGINT       DEFAULT NULL COMMENT '关联业务ID(如 novelId)',
    `is_read`     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    `create_time` DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_read` (`user_id`, `is_read`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='站内信表';

-- ----------------------------
-- 阅读进度表（每用户一行 = 「继续阅读」书签）
-- ----------------------------
DROP TABLE IF EXISTS `t_reading_progress`;
CREATE TABLE `t_reading_progress` (
    `id`            BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `novel_id`      BIGINT       NOT NULL COMMENT '小说ID',
    `chapter_id`    BIGINT       NOT NULL COMMENT '最后阅读章节ID',
    `novel_title`   VARCHAR(128) NOT NULL COMMENT '书名(冗余，供续读栏展示)',
    `chapter_no`    INT          NOT NULL COMMENT '章序号',
    `chapter_title` VARCHAR(128) NOT NULL COMMENT '章标题(冗余)',
    `mode`          VARCHAR(16)  NOT NULL DEFAULT 'scroll' COMMENT 'scroll滚动/page仿真翻页',
    `scroll_top`    INT          NOT NULL DEFAULT 0 COMMENT '滚动位置(滚动模式)',
    `page_no`       INT          NOT NULL DEFAULT 0 COMMENT '页码(仿真模式)',
    `client_time`   BIGINT       NULL COMMENT '客户端最后写入时间戳(ms，LWW冲突合并)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读进度表';

-- ----------------------------
-- 阅读偏好表（每用户一行）
-- ----------------------------
DROP TABLE IF EXISTS `t_reader_preference`;
CREATE TABLE `t_reader_preference` (
    `id`           BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`      BIGINT       NOT NULL COMMENT '用户ID',
    `font_size`    INT          NOT NULL DEFAULT 18 COMMENT '字号px',
    `font_family`  VARCHAR(16)  NOT NULL DEFAULT 'song' COMMENT 'song宋/hei黑/kai楷',
    `line_height`  DECIMAL(4,2) NOT NULL DEFAULT 2.00 COMMENT '行距',
    `column_width` INT          NOT NULL DEFAULT 680 COMMENT '栏宽px',
    `theme`        VARCHAR(16)  NOT NULL DEFAULT 'day' COMMENT 'day白天/sepia护眼/night夜间',
    `brightness`   DECIMAL(3,2) NOT NULL DEFAULT 1.00 COMMENT '亮度0.3~1',
    `mode`         VARCHAR(16)  NOT NULL DEFAULT 'scroll' COMMENT 'scroll/page',
    `auto_speed`   INT          NOT NULL DEFAULT 60 COMMENT '自动阅读速度px/秒',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读偏好表';

-- ----------------------------
-- 评论表（评论 / 回复共用一张表，parentId 区分层级）
-- ----------------------------
DROP TABLE IF EXISTS `t_comment`;
CREATE TABLE `t_comment` (
    `id`          BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL COMMENT '评论人用户ID',
    `novel_id`    BIGINT       NOT NULL COMMENT '小说ID',
    `chapter_id`  BIGINT       NULL COMMENT '章节ID(null=书评，非空=该章的章评)',
    `parent_id`   BIGINT       NULL COMMENT '父评论ID(null=顶层评论)',
    `content`     VARCHAR(500) NOT NULL COMMENT '评论内容',
    `like_count`  INT          NOT NULL DEFAULT 0 COMMENT '点赞数',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_novel` (`novel_id`, `parent_id`),
    KEY `idx_parent` (`parent_id`),
    KEY `idx_chapter` (`chapter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评论表';

-- ----------------------------
-- 书架表（收藏，user_id + novel_id 唯一）
-- ----------------------------
DROP TABLE IF EXISTS `t_bookshelf`;
CREATE TABLE `t_bookshelf` (
    `id`          BIGINT NOT NULL COMMENT '主键ID',
    `user_id`     BIGINT NOT NULL COMMENT '用户ID',
    `novel_id`    BIGINT NOT NULL COMMENT '小说ID',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_novel` (`user_id`, `novel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='书架表';

-- ----------------------------
-- 阅读历史表（追加写，书名/章名冗余）
-- uk_user_chapter 是 upsertIgnoreDuplicate 的依据：缺少该唯一键时 ON DUPLICATE KEY UPDATE
-- 不报错也不更新，而是插入新行，表现为同一章在历史里重复出现且无任何异常
-- ----------------------------
DROP TABLE IF EXISTS `t_read_history`;
CREATE TABLE `t_read_history` (
    `id`            BIGINT       NOT NULL COMMENT '主键ID',
    `user_id`       BIGINT       NOT NULL COMMENT '用户ID',
    `novel_id`      BIGINT       NOT NULL COMMENT '小说ID',
    `chapter_id`    BIGINT       NOT NULL COMMENT '章节ID',
    `chapter_no`    INT          NOT NULL COMMENT '章序号',
    `novel_title`   VARCHAR(128) NOT NULL COMMENT '书名(冗余)',
    `chapter_title` VARCHAR(128) NOT NULL COMMENT '章标题(冗余)',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_chapter` (`user_id`, `chapter_id`),
    KEY `idx_user` (`user_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅读历史表';

DROP TABLE IF EXISTS `t_feedback`;
CREATE TABLE `t_feedback` (
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

-- 管理端操作日志（审计追溯）：由后端 @AdminLogRecord + AdminLogAspect 自动写入，
-- 记录操作人、时间、目标内容与动作，成功与失败均留痕。
DROP TABLE IF EXISTS `t_admin_log`;
CREATE TABLE `t_admin_log` (
    `id`          BIGINT         NOT NULL COMMENT '主键ID',
    `admin_id`    BIGINT         NULL COMMENT '操作人用户ID',
    `admin_name`  VARCHAR(64)    NULL COMMENT '操作人账号（写入时快照，避免改名/删号后无法追溯）',
    `module`      VARCHAR(32)    NOT NULL COMMENT '模块：AUDIT/USER/ORDER/FEEDBACK/NOVEL/AI/IMPORT',
    `action`      VARCHAR(32)    NOT NULL COMMENT '动作：PASS/REJECT/IMPORT/STATUS/HANDLE/RESET/SAVE/DELETE',
    `target_type` VARCHAR(32)    NULL COMMENT '目标类型：NOVEL/CHAPTER/USER/FEEDBACK/AI_QUOTA',
    `target_id`   BIGINT         NULL COMMENT '目标主键',
    `summary`     VARCHAR(255)   NOT NULL COMMENT '操作摘要（人可读，列表直接展示）',
    `detail`      VARCHAR(1000)  NULL COMMENT '补充详情（拒绝理由/导入书名/变更值）',
    `ip`          VARCHAR(64)    NULL COMMENT '操作来源IP',
    `success`     TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否成功（0失败/1成功）',
    `error_msg`   VARCHAR(500)   NULL COMMENT '失败原因',
    `cost_ms`     BIGINT         NULL COMMENT '耗时毫秒',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_deleted`  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_admin_time` (`admin_id`, `create_time`),
    KEY `idx_module_time` (`module`, `create_time`),
    KEY `idx_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理端操作日志（审计追溯）';

-- ----------------------------
-- AI 全文审查任务
-- 三张表的分工：任务表记进度与累计消耗；每章结果表是幂等判据（uk_task_chapter），
-- 同时回答「这一章为什么没有结果」；问题表记可定位到章、段的明细。
-- 仅保留问题表不足以区分「审过且无问题」与「未审查成功」，两者都表现为查不到问题。
-- ----------------------------
DROP TABLE IF EXISTS `t_ai_review_task`;
CREATE TABLE `t_ai_review_task` (
    `id`               BIGINT         NOT NULL COMMENT '主键ID',
    `user_id`          BIGINT         NOT NULL COMMENT '发起人用户ID',
    `novel_id`         BIGINT         NOT NULL COMMENT '作品ID',
    `novel_title`      VARCHAR(128)   DEFAULT NULL COMMENT '书名快照（任务列表展示，作品改名不影响历史任务）',
    `scope_from_chapter_no` INT       DEFAULT NULL COMMENT '发起范围的下界章号（含）；NULL=不限（整本）',
    `scope_to_chapter_no`   INT       DEFAULT NULL COMMENT '发起范围的上界章号（含）；NULL=不限（整本）',
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

DROP TABLE IF EXISTS `t_ai_review_chapter`;
CREATE TABLE `t_ai_review_chapter` (
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
    UNIQUE KEY `uk_task_chapter` (`task_id`, `chapter_id`),
    KEY `idx_task_no` (`task_id`, `chapter_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 全文审查：每章结果';

DROP TABLE IF EXISTS `t_ai_review_issue`;
CREATE TABLE `t_ai_review_issue` (
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

-- ------------------------------------------------------------
-- 作品级专有名词表：跨章一致性核对的依据。
--
-- 审查一章时，本章人名/道具的写法与前面几章是否一致，不依赖模型是否查询前文：
-- 本书已确立的写法累积在本表中，由服务端核对（长度相同、仅一处字不同的写法直接报告）。
--
-- 全项目唯一一张不带 is_deleted 的业务表：本表为派生数据（作品删除后随之失效），
-- 且逻辑删除与唯一索引冲突：被逻辑删除的行仍占用 (novel_id, name)，
-- 重新累积同一名字时会静默触发唯一键冲突。生产环境用 sql/migration_add_novel_glossary.sql 增量建。
-- ------------------------------------------------------------
CREATE TABLE `t_novel_glossary` (
    `id`               BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    `novel_id`         BIGINT       NOT NULL COMMENT '作品ID',
    `name`             VARCHAR(32)  NOT NULL COMMENT '专有名词的标准写法（人名/地名/门派/功法/道具）',
    `first_chapter_id` BIGINT       NULL COMMENT '首次出现的章节ID',
    `first_chapter_no` INT          NULL COMMENT '首次出现的章号（给作者的依据就靠它）',
    `hit_count`        INT          NOT NULL DEFAULT 1 COMMENT '被审查到的次数',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_novel_name` (`novel_id`, `name`),
    KEY `idx_novel` (`novel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作品级专有名词表（跨章一致性核对依据）';

-- ------------------------------------------------------------
-- 作品级设定事实表：用途与名词表相同，记录的是数值（「伞骨=七根」），
-- 用于核对「前文写七根、本章写九根」这类矛盾。同样不带 is_deleted，理由同上。
-- 生产环境用 sql/migration_add_novel_fact.sql 增量建。
-- ------------------------------------------------------------
CREATE TABLE `t_novel_fact` (
    `id`               BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    `novel_id`         BIGINT       NOT NULL COMMENT '作品ID',
    `name`             VARCHAR(32)  NOT NULL COMMENT '被计量的东西（刀/伞骨/年龄），2~6 个字',
    `fact_value`       VARCHAR(32)  NOT NULL COMMENT '正文里原样出现的数值（含量词，如「七根」「两尺」）',
    `source`           VARCHAR(16)  NOT NULL DEFAULT 'model' COMMENT '数值来源：model=模型报的，scan=服务端从正文里扫出来的',
    `first_chapter_id` BIGINT       NULL COMMENT '首次出现的章节ID',
    `first_chapter_no` INT          NULL COMMENT '首次出现的章号（给作者的依据就靠它）',
    `hit_count`        INT          NOT NULL DEFAULT 1 COMMENT '被审查到的次数',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_novel_fact` (`novel_id`, `name`, `fact_value`),
    KEY `idx_novel` (`novel_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='作品级设定事实表（跨章数字核对依据）';

-- ----------------------------
-- MQ 出站消息（outbox）
-- 写库与投递消息在同一事务：无法投递的消息保留在表中，由定时任务补投。
-- 问题背景为「接口报错但数据已写入」：投递挂在事务的 afterCommit 中，
-- MQ 不可达时异常传播给调用方，而此时事务已提交。
-- 详见 sql/migration_add_mq_outbox.sql。
-- ----------------------------
DROP TABLE IF EXISTS `t_mq_outbox`;
CREATE TABLE `t_mq_outbox` (
    `id`            BIGINT           NOT NULL COMMENT '主键（雪花 ID，与全项目一致）',
    `exchange`      VARCHAR(128)     NOT NULL COMMENT '交换机',
    `routing_key`   VARCHAR(128)     NOT NULL COMMENT '路由键',
    `payload_type`  VARCHAR(255)     NOT NULL COMMENT '消息类全限定名（重投时反序列化回原类型）',
    `payload`       TEXT             NOT NULL COMMENT '消息体 JSON',
    `status`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=待投 1=已投 2=放弃（重试用尽）',
    `attempts`      INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '已投递尝试次数',
    `next_retry_at` DATETIME         NOT NULL COMMENT '到点才重投（指数退避，封顶 5 分钟）',
    `last_error`    VARCHAR(500)     NULL COMMENT '最后一次失败原因（截断到 500 字）',
    `create_time`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `sent_time`     DATETIME         NULL COMMENT '投出去的时间',
    PRIMARY KEY (`id`),
    KEY `idx_pending` (`status`, `next_retry_at`),
    KEY `idx_sent` (`status`, `sent_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ 出站消息（与业务数据同一事务落库，投不出去由定时任务补投）';

-- ============================================================
-- 种子数据：角色、分类（用户由应用启动时自动初始化，密码 BCrypt 加密）。
--
-- 作品数据（书籍 + 章节正文）不在本文件维护：由管理端「公版书导入」产生，
-- 再用 scripts/export-seed.sh 导出成 sql/seed_novels.sql.gz，容器首次初始化时
-- 按文件名顺序在 init.sql 之后自动执行（见 docker-compose.prod.yml）。
--
-- 本文件原有的 6 本「示例小说」手写 INSERT 为节选拼接的示例数据（total_chapters
-- 固定写 2、阅读量写 200 万），会与真实书目混在一起，已删除。
-- ============================================================

INSERT INTO `t_role` (`id`, `role_code`, `role_name`, `create_time`) VALUES
(1, 'admin', '超级管理员', NOW()),
(2, 'user', '普通用户', NOW());

-- 7 个分类，按书库的实际题材划分（公版古籍 59 部 + 原创连载 1 部）。
-- 主键与前端书封配色（frontend/src/utils/cover.js）一一对应，改动需同步；
-- sort 决定前台分类的先后顺序。
INSERT INTO `t_category` (`id`, `name`, `parent_id`, `sort`, `status`, `create_time`, `update_time`, `is_deleted`) VALUES
(1, '古典名著', 0, 1, 1, NOW(), NOW(), 0),
(2, '仙侠修真', 0, 7, 1, NOW(), NOW(), 0),
(3, '侠义公案', 0, 3, 1, NOW(), NOW(), 0),
(4, '历史演义', 0, 2, 1, NOW(), NOW(), 0),
(5, '志怪神魔', 0, 4, 1, NOW(), NOW(), 0),
(6, '世情讽喻', 0, 5, 1, NOW(), NOW(), 0),
(7, '儿女英雄', 0, 6, 1, NOW(), NOW(), 0);

