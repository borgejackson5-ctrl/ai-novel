package com.ainovel.common.mq;

/**
 * 一条待投或已投的 MQ 出站消息（{@code t_mq_outbox} 的一行）。
 *
 * <p>消息写入数据库而非仅存内存的原因：投递动作原先挂在事务的 {@code afterCommit} 中，
 * MQ 不可达时异常会传播给调用方，而事务已经提交，表现为「接口报错但数据已写入」
 * （发布作品曾返回 500，而作品与章节已入库）。写入数据库后消息与业务数据处于同一事务，
 * 投递失败即保留在表中，由 {@link MqOutboxResendTask} 退避重投。
 *
 * @param id          outbox 主键（同时用作 MQ correlationId 的前缀，便于将 confirm 回调对回该行）
 * @param exchange    交换机
 * @param routingKey  路由键
 * @param payloadType 消息类全限定名。重投时按它反序列化回原对象，
 *                    使再次序列化后的线上格式与首次投递完全一致（含 {@code __TypeId__} 头）
 * @param payload     消息体 JSON
 * @param attempts    已尝试投递的次数（0 表示尚未尝试）
 */
public record MqOutboxRecord(long id, String exchange, String routingKey,
                             String payloadType, String payload, int attempts) {
}
