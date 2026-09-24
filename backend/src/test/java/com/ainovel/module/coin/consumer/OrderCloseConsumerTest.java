package com.ainovel.module.coin.consumer;

import com.ainovel.module.coin.domain.message.OrderCloseMessage;
import com.ainovel.module.coin.service.CoinService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 关单消费者单测：手动 ack + 本地重试 3 次 + 失败转死信（nack 不重入队）
 */
@ExtendWith(MockitoExtension.class)
class OrderCloseConsumerTest {

    @Mock
    private CoinService coinService;

    @Mock
    private Channel channel;

    private OrderCloseConsumer consumer;

    @BeforeEach
    void initService() {
        consumer = new OrderCloseConsumer(coinService);
    }

    @Test
    @DisplayName("关单成功 → basicAck")
    void closeSuccess_ack() throws Exception {
        OrderCloseMessage msg = new OrderCloseMessage();
        msg.setOrderNo("NO123");

        consumer.onMessage(msg, channel, 1L);

        verify(coinService).closeTimeoutOrder("NO123");
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("连续失败 3 次 → basicNack 不重入队（进死信）")
    void closeFail3Times_nackToDlx() throws Exception {
        OrderCloseMessage msg = new OrderCloseMessage();
        msg.setOrderNo("NO123");
        doThrow(new RuntimeException("db down")).when(coinService).closeTimeoutOrder(anyString());

        consumer.onMessage(msg, channel, 1L);

        verify(coinService, times(3)).closeTimeoutOrder("NO123");
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
