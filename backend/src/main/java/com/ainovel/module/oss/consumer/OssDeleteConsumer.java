package com.ainovel.module.oss.consumer;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.message.OssDeleteMessage;
import com.ainovel.module.oss.service.OssService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * OSS 删除消费者：手动 ack + 本地指数退避重试 3 次 + 失败转死信
 *
 * <p>消费「删封面」消息，异步删除 OSS 对象。失败时本地退避重试 3 次（覆盖 OSS 瞬时抖动），
 * 仍失败则 nack 且不重新入队，消息转入死信队列留痕，由人工或对账任务处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OssDeleteConsumer {

    private static final int MAX_RETRY = 3;

    /** 退避基数（毫秒）：第 i 次失败后等待 base &lt;&lt; i，即 500ms / 1000ms */
    private static final long RETRY_BACKOFF_BASE_MS = 500L;

    private final OssService ossService;

    @RabbitListener(queues = MqConstant.OSS_DELETE_QUEUE)
    public void onMessage(OssDeleteMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("收到 OSS 删除消息: url={}", message.getUrl());

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                ossService.deleteByUrl(message.getUrl());
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("OSS 删除失败，第 {} 次重试: url={}", attempt, message.getUrl(), e);
                // 最后一次尝试失败后不再等待，直接转死信
                if (attempt < MAX_RETRY) {
                    sleepQuietly(RETRY_BACKOFF_BASE_MS << (attempt - 1));
                }
            }
        }

        // ack/nack 置于重试循环外（与 AiAuditConsumer 同因）：ack 自身抛出 IOException 时
        // 不应被当作业务失败再次重试。
        if (lastError == null) {
            channel.basicAck(deliveryTag, false);
        } else {
            // 重试耗尽仍失败 -> 拒绝且不重新入队，消息进入死信队列
            log.error("OSS 删除最终失败，转入死信队列: url={}", message.getUrl(), lastError);
            channel.basicNack(deliveryTag, false, false);
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
