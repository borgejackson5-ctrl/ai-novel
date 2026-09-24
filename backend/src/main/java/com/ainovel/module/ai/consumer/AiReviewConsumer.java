package com.ainovel.module.ai.consumer;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.message.AiReviewMessage;
import com.ainovel.module.ai.service.AiReviewTaskService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 全文审查消费者：一条消息 = 一章。
 *
 * <p>手动 ack + 本地指数退避重试 3 次 + 失败转死信，与 {@code AiAuditConsumer} 一致。
 * 但**重试的对象不同**：这里重试的是「基础设施异常」（DB 抖动、代码缺陷抛出的异常）；
 * 模型侧的失败（超时、返回无法解析）已由 {@code AiReviewTaskService.processChapter}
 * 转为「该章未审成」写入数据库，不会进入重试，因为该类失败重试结果相同，
 * 仅会重复消耗一次额度。
 *
 * <p>**并发度固定为 1**：
 * <ul>
 *   <li>全书审查为连续几百次调用，同时执行多个任务会使平台 Key 触发限流；</li>
 *   <li>「逐章推进」的语义是作者可理解的进度，并发消费会导致进度条跳变；</li>
 *   <li>同一条消息也不会被并发处理，因此「查占位行 → 插入 → 审查」这条链无需分布式锁
 *       （唯一索引为第二道保障，而非唯一保障）。</li>
 * </ul>
 * 多个作者同时使用时进入排队，此为异步任务的预期行为。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiReviewConsumer {

    private static final int MAX_RETRY = 3;

    /** 退避基数（毫秒）：第 i 次失败后等待 base &lt;&lt; i，即 500ms / 1000ms */
    private static final long RETRY_BACKOFF_BASE_MS = 500L;

    private final AiReviewTaskService aiReviewTaskService;

    @RabbitListener(queues = MqConstant.AI_REVIEW_QUEUE, concurrency = "1")
    public void onMessage(AiReviewMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Long taskId = message.getTaskId();
        Long chapterId = message.getChapterId();
        log.info("收到全文审查消息: taskId={} chapterId={}", taskId, chapterId);

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                aiReviewTaskService.processChapter(taskId, chapterId);
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("全文审查失败，第 {} 次重试: taskId={} chapterId={}", attempt, taskId, chapterId, e);
                if (attempt < MAX_RETRY) {
                    sleepQuietly(RETRY_BACKOFF_BASE_MS << (attempt - 1));
                }
            }
        }

        if (lastError == null) {
            // ack 置于重试循环外（与 AiAuditConsumer 相同原因）：ack 自身抛出 IOException 时
            // 不应被判定为「该章未审成」并再次执行，否则会重复调用模型、重复扣减额度。
            channel.basicAck(deliveryTag, false);
            return;
        }

        // 重试耗尽：先将该章记为「未审成」，再使消息进入死信队列。
        // 顺序不可颠倒：若仅进入死信队列，该章会一直停留在「审查中」，
        // 任务进度永远无法到达 100%，界面表现为无法结束的进度条。
        log.error("全文审查最终失败，转入死信队列: taskId={} chapterId={}", taskId, chapterId, lastError);
        recordAbandoned(taskId, chapterId, lastError);
        channel.basicNack(deliveryTag, false, false);
    }

    /**
     * 将该章标记为未审成并收尾。
     *
     * <p>外层包 try：收尾本身失败（DB 不可用）时仍需使消息进入死信队列，
     * 否则 ack/nack 均无法执行，消息会在此处反复重投并填满日志。
     */
    private void recordAbandoned(Long taskId, Long chapterId, Exception cause) {
        try {
            aiReviewTaskService.abandonChapter(taskId, chapterId, "这次审查没能完成，稍后可以重试");
        } catch (Exception e) {
            log.error("全文审查：标记章节失败时又出错 taskId={} chapterId={}", taskId, chapterId, e);
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
