package com.ainovel.module.search.consumer;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.message.ChapterChunkSyncMessage;
import com.ainovel.module.search.service.ChapterVectorService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 章节块同步消费者单测：ack / 配置类失败不重投 / 退避重投 / 转死信。
 *
 * <p>这一层必须有测试的原因：它修复的是「索引中没有新章节，而且不报错」这类静默缺陷。
 * 消费逻辑写错时，症状与修复前相同（检索不到、日志干净），肉眼无法识别。
 */
@ExtendWith(MockitoExtension.class)
class ChapterChunkSyncConsumerTest {

    @Mock
    private ChapterVectorService chapterVectorService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Channel channel;

    private ChapterChunkSyncConsumer consumer;

    @BeforeEach
    void initConsumer() {
        // 项目约定：不用 @InjectMocks，直接按生产类 final 字段顺序构造
        consumer = new ChapterChunkSyncConsumer(chapterVectorService, stringRedisTemplate);
    }

    private ChapterChunkSyncMessage message() {
        ChapterChunkSyncMessage message = new ChapterChunkSyncMessage();
        message.setNovelId(100L);
        message.setChapterId(200L);
        return message;
    }

    @Test
    @DisplayName("重建成功 → basicAck，并清掉重试计数")
    void success_ack() throws Exception {
        when(chapterVectorService.indexChapter(100L, 200L)).thenReturn(8);

        consumer.onMessage(message(), channel, 1L);

        verify(chapterVectorService).indexChapter(100L, 200L);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(stringRedisTemplate).delete("search:chunk:retry:200");
    }

    @Test
    @DisplayName("配置/参数类失败（BusinessException）→ 直接 ack，不重投、不进死信")
    void businessError_ackWithoutRetry() throws Exception {
        // 典型场景：未配置向量模型的 Key。重投一百次仍是同一个错误，只会阻塞队列
        when(chapterVectorService.indexChapter(100L, 200L))
                .thenThrow(new BusinessException(ErrorCode.AI_GENERATE_FAIL, "未配置文本向量模型的 Key"));

        consumer.onMessage(message(), channel, 1L);

        verify(chapterVectorService, times(1)).indexChapter(100L, 200L);
        verify(channel).basicAck(1L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("普通异常、重试计数未到上限 → basicNack 重入队（退避后重投）")
    void transientError_requeue() throws Exception {
        when(chapterVectorService.indexChapter(100L, 200L))
                .thenThrow(new IllegalStateException("es down"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("search:chunk:retry:200")).thenReturn(1L);

        consumer.onMessage(message(), channel, 1L);

        verify(channel).basicNack(1L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        // 计数从 0 变 1 时给这个 key 设 TTL（1 小时后自动消失，不残留）
        verify(stringRedisTemplate).expire("search:chunk:retry:200", Duration.ofHours(1));
    }

    @Test
    @DisplayName("重试计数 TTL 只在第一次设：第 2 次起不再续期，否则这个 key 永远不过期")
    void retryCounter_ttlSetOnlyOnce() throws Exception {
        when(chapterVectorService.indexChapter(100L, 200L))
                .thenThrow(new IllegalStateException("es down"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("search:chunk:retry:200")).thenReturn(2L);

        consumer.onMessage(message(), channel, 1L);

        verify(channel).basicNack(1L, false, true);
        // 若无条件 expire，每次失败都会把 TTL 续满 1 小时，只要仍在失败就永不过期，
        // 与「1 小时自动过期」的说法相反。判据与 FixedWindowRateLimiter / VerifyCodeService 一致。
        verify(stringRedisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("连续失败到上限 → basicNack 不重入队（进死信留痕）")
    void exhausted_nackToDlx() throws Exception {
        when(chapterVectorService.indexChapter(100L, 200L))
                .thenThrow(new IllegalStateException("es down"));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 计数已达上限：第一次消费即应转死信（同一失败重复 5 次结果相同，无需真等 5 轮退避）
        when(valueOperations.increment(anyString())).thenReturn(5L);

        consumer.onMessage(message(), channel, 1L);

        verify(channel).basicNack(1L, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("消息缺 id → 丢弃（ack），不重投也不进死信")
    void brokenMessage_discarded() throws Exception {
        ChapterChunkSyncMessage broken = new ChapterChunkSyncMessage();
        broken.setNovelId(100L);

        consumer.onMessage(broken, channel, 1L);

        verify(chapterVectorService, never()).indexChapter(any(), any());
        verify(channel).basicAck(eq(1L), eq(false));
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }
}
