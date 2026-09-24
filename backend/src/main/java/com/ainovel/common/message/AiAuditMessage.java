package com.ainovel.common.message;

import com.ainovel.common.mq.OutboxIdAware;
import lombok.Data;

import java.io.Serializable;

/**
 * AI 审核消息
 */
@Data
public class AiAuditMessage implements Serializable, OutboxIdAware {

    private Long novelId;

    /** 章节 ID：null 表示整本审核（发布作品），非 null 表示单章审核（连载/改章） */
    private Long chapterId;

    /**
     * 出站消息主键（非业务字段）。
     *
     * <p>由 {@code MqOutboxStore.save} 在序列化前写入（见 {@link OutboxIdAware}）。
     * 消费端以其为去重键：同一条消息被重投时直接跳过，否则会重复调用模型、重复扣减平台额度、
     * 重复向管理员发送待审通知。用户重新提交产生的是**新的 outbox 行**，主键不同，正常审核。
     */
    private Long outboxId;
}
