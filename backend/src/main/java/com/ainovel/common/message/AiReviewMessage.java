package com.ainovel.common.message;

import lombok.Data;

import java.io.Serializable;

/**
 * AI 全文审查消息：**一章一条**。
 *
 * <p>不采用「一条消息携带整个任务、消费者内部遍历整本书」的原因：
 * 前者 ack 粒度即为整本书，进程退出、发布重启、消费者超时均只能从头重来，
 * 而重来意味着同一批章节被重复调用、重复扣减额度。
 * 按章投递时，崩溃仅影响当前章，重投也会被 {@code uk_task_chapter} 拦截。
 *
 * <p>消息中只携带 id（taskId / chapterId），正文与配置**由消费者回查 DB 获取**，
 * 与项目中其它 MQ 消费的约定一致：投递时写入快照，消费时可能已过期（作者修改了章节、
 * 更换了 Key），唯一可靠依据是消费时刻的数据库。
 *
 * <p>置于 {@code common/message} 而非 AI 模块内：消息类属于跨模块契约，
 * 放在任一侧都会产生反向依赖（见架构约定）。注意：修改包名会使队列中积压消息的
 * {@code __TypeId__} 反序列化失败。
 */
@Data
public class AiReviewMessage implements Serializable {

    /** 审查任务 ID（{@code t_ai_review_task.id}） */
    private Long taskId;

    /** 本章要审的章节 ID */
    private Long chapterId;
}
