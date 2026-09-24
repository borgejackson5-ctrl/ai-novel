package com.ainovel.module.coin.consumer;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.module.coin.domain.message.OrderCloseMessage;
import com.ainovel.module.coin.service.CoinService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 充值订单超时关单消费者：手动 ack + 本地指数退避重试 3 次 + 失败转死信
 *
 * <p>消费延迟队列到期的关单消息，把仍未支付的订单置为已取消。关单本身幂等
 * （{@code markCanceled} 只处理 status=0 的订单），已支付/已取消均跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCloseConsumer {

    private static final int MAX_RETRY = 3;
    private static final long RETRY_BACKOFF_BASE_MS = 500L;

    private final CoinService coinService;

    @RabbitListener(queues = MqConstant.PAY_CLOSE_QUEUE)
    public void onMessage(OrderCloseMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        log.info("收到关单消息: orderNo={}", message.getOrderNo());

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                coinService.closeTimeoutOrder(message.getOrderNo());
                lastError = null;
                break;
            } catch (Exception e) {
                lastError = e;
                log.warn("关单处理失败，第 {} 次重试: orderNo={}", attempt, message.getOrderNo(), e);
                if (attempt < MAX_RETRY) {
                    sleepQuietly(RETRY_BACKOFF_BASE_MS << (attempt - 1));
                }
            }
        }

        // ack/nack 置于重试循环外（与 AiAuditConsumer 同理）：ack 自身抛 IOException 时
        // 不应按业务失败再重试一次。关单幂等，重复执行无意义。
        if (lastError == null) {
            channel.basicAck(deliveryTag, false);
        } else {
            log.error("关单处理最终失败，转入死信队列: orderNo={}", message.getOrderNo(), lastError);
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
