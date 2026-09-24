package com.ainovel.common.mq;

/**
 * 「消息体可接收出站消息主键」的标记接口。
 *
 * <p>MQ 投递语义为 at-least-once（见 {@link MqOutboxDispatcher} 的说明），同一条消息可能被投递两次：
 * 一次为「已投出但标记未写入数据库」，一次为 broker 重投。消费端要幂等，需先能判定两条消息是否为同一条。
 *
 * <p>仅凭业务字段无法判定：{@code novelId + chapterId} 可能重复，用户修改稿件后再次发布属于
 * **另一条消息**，应当重新审核。因此由 {@link MqOutboxStore#save} 将 outbox 主键写入消息体，
 * 消费端以其为去重键：同一主键重投多次仅实际执行一次，主键变化即为新消息。
 *
 * <p>注意：写入必须发生在**序列化之前**。投递时使用的是库中那份 JSON
 * （{@code MqOutboxDispatcher.send} 按 {@code payloadType} 反序列化后投递），
 * 序列化后再向对象写入字段，投出的仍是旧值。
 *
 * <p>未实现该接口的消息类型不做处理，该链路维持原样，消费端幂等需自行实现。
 */
public interface OutboxIdAware {

    /** 写入出站消息主键（由 {@link MqOutboxStore#save} 调用，业务代码不应调用） */
    void setOutboxId(Long outboxId);

    /** 出站消息主键；改造前写入 outbox 的旧消息为 null，表示无法去重 */
    Long getOutboxId();
}
