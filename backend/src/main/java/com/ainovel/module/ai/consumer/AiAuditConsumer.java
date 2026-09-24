package com.ainovel.module.ai.consumer;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.message.AiAuditMessage;
import com.ainovel.module.ai.service.AiAuditService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * AI 审核消息消费者：手动 ack + 本地指数退避重试 3 次 + 失败转死信队列 + 消息级去重。
 *
 * <p>**去重的必要性**：投递语义为 at-least-once（已投递但标记未落库、或 ack 失败后
 * broker 重投，均会使同一条消息再次出现），而该消费者执行的三项操作均有副作用：
 * **调用模型**（产生费用）、**扣减平台额度**、**向管理员发送待审通知**。重投一次即重复执行三项操作。
 *
 * <p>去重键取消息体中的 outbox 主键（由 {@code MqOutboxStore.save} 写入，见 {@code OutboxIdAware}），
 * 其可区分「同一条消息的重投」与「用户重新提交产生的新消息」，后者为新的 outbox 行、主键不同。
 * 不能以 novelId/chapterId 作为键：用户修改稿件后再次提交时，这两个字段完全相同。
 *
 * <p>去重为 **fail-open**：Redis 不可用时退化为「不去重」（与改造前行为一致），
 * 而非使消息阻塞。该策略与限流一致、与幂等键相反，涉及资金的功能采用 fail-closed，此处不适用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiAuditConsumer {

    private static final int MAX_RETRY = 3;

    /** 退避基数（毫秒）：第 i 次失败后等待 base &lt;&lt; i，即 500ms / 1000ms */
    private static final long RETRY_BACKOFF_BASE_MS = 500L;

    /** 去重键前缀：`audit:done:{outboxId}` */
    private static final String DEDUP_KEY_PREFIX = "audit:done:";

    /**
     * 去重键的存活时长。
     *
     * <p>仅需覆盖「同一条消息可能再次出现」的时间窗口，取 1 天已足够宽裕：补投任务的退避
     * 10s→20s→…→封顶 5 分钟、最多 8 次，全部执行完毕约 20 分钟；broker 重投发生在连接断开时。
     * 更长的 TTL 只会额外占用 Redis 内存（每条审核消息对应一个键）。
     */
    private static final Duration DEDUP_TTL = Duration.ofHours(24);

    private final AiAuditService aiAuditService;

    private final StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = MqConstant.AI_AUDIT_QUEUE)
    public void onMessage(AiAuditMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        // chapterId 非空 = 单章审核（连载/改章）；空 = 整本审核（发布作品）
        boolean chapter = message.getChapterId() != null;
        String key = chapter ? "chapterId=" + message.getChapterId() : "novelId=" + message.getNovelId();

        // outboxId 为 null 表示改造前写入 outbox 的历史消息，无法判断是否同一条，只能按原样处理
        String dedupKey = message.getOutboxId() == null ? null : DEDUP_KEY_PREFIX + message.getOutboxId();
        if (alreadyDone(dedupKey)) {
            log.info("审核消息已处理过，跳过（消费幂等）: {}，dedupKey={}", key, dedupKey);
            channel.basicAck(deliveryTag, false);
            return;
        }
        log.info("收到审核消息: {}", key);

        // 本地指数退避重试，避免瞬时故障导致误判与无效执行。
        // 该段**仅包含业务调用**，不包含 ack，理由见下方注释。
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                if (chapter) {
                    aiAuditService.auditChapter(message.getChapterId());
                } else {
                    aiAuditService.audit(message.getNovelId());
                }
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("审核失败，第 {} 次重试: {}", attempt, key, e);
                // 最后一次尝试失败后不再等待，直接转死信
                if (attempt < MAX_RETRY) {
                    sleepQuietly(RETRY_BACKOFF_BASE_MS << (attempt - 1));
                }
            }
        }

        // ack / nack 刻意置于重试循环**之外**：
        // basicAck 自身也会抛出 IOException（连接已断开等），若置于上方 try 中，
        // 会被判定为「业务失败」并再次重试，即业务重复执行一次；而此处涉及**调用模型、扣减额度**，
        // 其代价为重复计费。置于循环外后，ack 失败仅意味着消息被 broker 重投，
        // 由下一次消费正常处理（该次为实际意义上的重试）。
        if (lastError == null) {
            // 先写去重标记、再 ack：若顺序相反且标记写入失败而 ack 成功，
            // 消息不会被重投，该次「未记录」将无法被发现；而先标记后 ack 即便 ack 失败，
            // 重投时也会被标记拦截。顺序颠倒等同于将幂等性建立在「Redis 必定写入成功」的前提上。
            markDone(dedupKey);
            channel.basicAck(deliveryTag, false);
            log.info("审核完成: {}", key);
        } else {
            // 重试耗尽仍失败 -> 拒绝且不重新入队，消息进入死信队列
            log.error("审核最终失败，转入死信队列: {}", key, lastError);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /** 该消息是否已成功处理（fail-open：Redis 不可用时按「未处理过」处理） */
    private boolean alreadyDone(String dedupKey) {
        if (dedupKey == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(dedupKey));
        } catch (Exception e) {
            // 此处**不能抛出异常**：抛出后监听器会判定本条处理失败，而业务实际尚未开始执行，
            // 结果是消息被反复重投，未执行任何业务却反复进入重试。退化为不去重，与改造前一致。
            log.warn("审核去重键查询失败，本条退化为不去重: {}", dedupKey, e);
            return false;
        }
    }

    /** 记录「该消息已成功处理」（fail-soft：写入失败仅告警，下次重投可能重复执行一次） */
    private void markDone(String dedupKey) {
        if (dedupKey == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForValue().set(dedupKey, "1", DEDUP_TTL);
        } catch (Exception e) {
            log.warn("审核去重键写入失败，若本条被重投会重复执行一次: {}", dedupKey, e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
