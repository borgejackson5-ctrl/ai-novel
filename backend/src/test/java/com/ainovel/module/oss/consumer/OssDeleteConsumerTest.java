package com.ainovel.module.oss.consumer;

import com.ainovel.common.message.OssDeleteMessage;
import com.ainovel.module.oss.service.OssService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OSS 删除消费者单测：手动 ack + 本地重试 3 次 + 失败转死信（nack 不重入队）。
 */
@ExtendWith(MockitoExtension.class)
class OssDeleteConsumerTest {

    @Mock
    private OssService ossService;

    @Mock
    private Channel channel;

    private OssDeleteConsumer consumer;

    @BeforeEach
    void initService() {
        consumer = new OssDeleteConsumer(ossService);
    }

    @Test
    @DisplayName("删除成功 → basicAck")
    void deleteSuccess_ack() throws Exception {
        OssDeleteMessage msg = new OssDeleteMessage();
        msg.setUrl("https://cdn.example.com/ai-novel/covers/abc.png");

        consumer.onMessage(msg, channel, 1L);

        verify(ossService).deleteByUrl("https://cdn.example.com/ai-novel/covers/abc.png");
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("连续失败 3 次 → basicNack 不重入队（进死信）")
    void deleteFail3Times_nackToDlx() throws Exception {
        OssDeleteMessage msg = new OssDeleteMessage();
        msg.setUrl("https://cdn.example.com/ai-novel/covers/abc.png");
        doThrow(new RuntimeException("oss down")).when(ossService).deleteByUrl(anyString());

        consumer.onMessage(msg, channel, 1L);

        verify(ossService, times(3)).deleteByUrl("https://cdn.example.com/ai-novel/covers/abc.png");
        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    /**
     * ack 自身也会抛 IOException（连接断开），此时业务不应再执行一遍。
     *
     * <p>若 {@code basicAck} 写在业务那个 try 内：它一抛就被当作「业务失败」，
     * 循环重试 3 次即业务重复执行 3 次。OSS 删除本身幂等，看不出问题，
     * 但同样的写法用在审核/审查消费者上就是重复调用模型、重复扣额度。
     * 该用例约束「ack 必须在重试循环之外」。
     */
    @Test
    @DisplayName("basicAck 自身抛异常 → 业务只执行一次，异常直接抛出（交给 broker 重投）")
    void ackItselfFails_businessRunsOnlyOnce() throws Exception {
        OssDeleteMessage msg = new OssDeleteMessage();
        msg.setUrl("https://cdn.example.com/ai-novel/covers/abc.png");
        doThrow(new java.io.IOException("channel closed")).when(channel).basicAck(anyLong(), anyBoolean());

        assertThrows(java.io.IOException.class, () -> consumer.onMessage(msg, channel, 1L));

        verify(ossService, times(1)).deleteByUrl(anyString());
    }
}
