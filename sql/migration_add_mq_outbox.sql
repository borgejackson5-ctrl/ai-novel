-- ============================================================
-- MQ 出站消息表（outbox）：将「写库」与「投递消息」纳入同一事务
--
-- 背景：原实现投递均走 MqSender.sendAfterCommit，动作注册在事务的 afterCommit
-- 回调中。MQ 不可达时该异常会传播给调用方（Spring 语义：异常向外抛出，但事务
-- 仍然视为已提交），导致接口报错而数据已写入。
-- MQ 不可达时 POST /novel/publish 返回 500 而作品与章节已在库中，
-- 调用方重试一次即多出一本；下单、充值链路同理（多一笔）。
--
-- 实现：消息先与业务数据一并写入本表（同一事务，原子），提交后再投递；
-- 投递失败则保留在表中，由定时任务退避重投。由此保证「接口成功 ⇒ 数据已写入、消息最终投出」。
-- 同时覆盖三条无定时对账的链路：章节块索引、OSS 文件清理、订单关单延迟消息
-- （作品→ES 链路本有对账任务兜底，也一并走这里）。
--
-- 状态：0=待投 1=已投 2=放弃（重试用尽，需人工排查，日志会打 ERROR）
-- 已投记录只保留最近 7 天，由补投任务一并清理，表不会无限增长。
--
-- 本脚本幂等（CREATE TABLE IF NOT EXISTS），重复执行不报错。
-- 与同目录其它 migration 一致：不带 USE，执行时必须显式指定库。
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_mq_outbox` (
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
