package com.ainovel.module.rank.consumer;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.message.NovelReadMessage;
import com.ainovel.module.rank.service.RankService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 阅读上报消费者：把「刚发生了一次阅读」反映到热门榜的 Redis 分数上。
 *
 * <p><b>与其他消费者的差异（有意为之）：此处不重试。</b>
 * 搜索同步失败会导致「搜不到」，必须退避重投；而本动作失败仅使榜单少加一次分，
 * 重投可能重复加分（{@code incrementScore} 非幂等）。因此失败时直接确认消息、
 * 记录一条 ERROR 日志；热度为派生指标，下一次榜单回源重建时会按真实阅读量补回。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NovelReadConsumer {

    private final RankService rankService;

    @RabbitListener(queues = MqConstant.RANK_READ_QUEUE)
    public void onMessage(NovelReadMessage message, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            rankService.incrHot(message.getNovelId());
        } catch (Exception e) {
            log.error("阅读热度上报失败（不重试：热度是派生指标，榜单重建时会补回）: novelId={}",
                    message.getNovelId(), e);
        }
        channel.basicAck(deliveryTag, false);
    }
}
