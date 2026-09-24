package com.ainovel.module.ai.consumer;

import com.ainovel.common.message.AiAuditMessage;
import com.ainovel.module.ai.service.AiAuditService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审核消费者的幂等：同一条消息重投只真正执行一次。
 *
 * <p>这里执行的三件事都有副作用：调用模型（产生费用）、扣除平台额度、发送管理员通知，
 * 因此「重投被跳过」不是优化，而是正确性要求。
 *
 * <p>去重键使用 outbox 主键而不是 novelId/chapterId：用户改稿后重新提交时，
 * 那两个字段完全相同，用它们作为键会把真正的新审核跳过
 * （这一条在 {@link #differentOutboxIdIsAuditedAgain} 中约束）。
 */
@ExtendWith(MockitoExtension.class)
class AiAuditConsumerTest {

    private static final long DELIVERY_TAG = 1L;

    @Mock
    private AiAuditService aiAuditService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private Channel channel;

    /** 用真实 Set 替代 Redis：使「写入」与「读取」在同一用例内连贯 */
    private final Set<String> redisKeys = ConcurrentHashMap.newKeySet();

    private AiAuditConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AiAuditConsumer(aiAuditService, stringRedisTemplate);
    }

    /**
     * 装配一个「能记住写入」的假 Redis。
     *
     * <p>刻意不在 {@code @BeforeEach} 中无条件装配：Mockito 的严格模式会把「本用例未使用」的桩
     * 判定为 UnnecessaryStubbing。而「是否访问 Redis」本身就是若干用例的断言点
     * （历史消息不应访问、Redis 故障时需要退化为不去重），无条件装配会混淆这些主张。
     */
    private void enableRedis() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.hasKey(anyString()))
                .thenAnswer(inv -> redisKeys.contains(inv.getArgument(0, String.class)));
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        doAnswer(inv -> {
            redisKeys.add(inv.getArgument(0, String.class));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    private AiAuditMessage chapterMessage(long chapterId, Long outboxId) {
        AiAuditMessage message = new AiAuditMessage();
        message.setNovelId(100L);
        message.setChapterId(chapterId);
        message.setOutboxId(outboxId);
        return message;
    }

    @Test
    void firstDeliveryRunsTheAuditAndMarksDone() throws Exception {
        enableRedis();
        AiAuditMessage message = chapterMessage(9L, 77L);

        consumer.onMessage(message, channel, DELIVERY_TAG);

        verify(aiAuditService).auditChapter(9L);
        assertTrue(redisKeys.contains("audit:done:77"), () -> "应当留下去重标记，实际：" + redisKeys);
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    /** 核心用例：同一条消息再来一次（「已投出但标记未写入库」或 broker 重投），业务不得重复执行 */
    @Test
    void redeliveryOfSameMessageIsSkipped() throws Exception {
        enableRedis();
        AiAuditMessage message = chapterMessage(9L, 77L);

        consumer.onMessage(message, channel, DELIVERY_TAG);
        consumer.onMessage(message, channel, DELIVERY_TAG);

        verify(aiAuditService, times(1)).auditChapter(9L);
        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
    }

    /** 用户改稿后重新提交 = 新的 outbox 行，必须照常审核，不能被去重跳过 */
    @Test
    void differentOutboxIdIsAuditedAgain() throws Exception {
        enableRedis();
        consumer.onMessage(chapterMessage(9L, 77L), channel, DELIVERY_TAG);
        consumer.onMessage(chapterMessage(9L, 78L), channel, DELIVERY_TAG);

        verify(aiAuditService, times(2)).auditChapter(9L);
    }

    /** 无 outbox 主键的历史消息：无法判定是否同一条，只能照常执行 */
    @Test
    void legacyMessageWithoutOutboxIdRunsAndDoesNotTouchRedis() throws Exception {
        consumer.onMessage(chapterMessage(9L, null), channel, DELIVERY_TAG);

        verify(aiAuditService).auditChapter(9L);
        verify(stringRedisTemplate, never()).hasKey(anyString());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    /** Redis 不可用时退化为不去重，不能让消息停留在失败状态（fail-open，与限流一致） */
    @Test
    void redisDownFallsBackToRunningTheAudit() throws Exception {
        AiAuditMessage message = chapterMessage(9L, 77L);
        // 完全不可用意味着两个操作都失败（读键与获取 ops 均抛异常），而不是只有读失败
        when(stringRedisTemplate.hasKey(anyString()))
                .thenThrow(new RedisConnectionFailureException("redis down"));
        when(stringRedisTemplate.opsForValue())
                .thenThrow(new RedisConnectionFailureException("redis down"));

        consumer.onMessage(message, channel, DELIVERY_TAG);

        verify(aiAuditService).auditChapter(9L);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    /** 业务失败转死信，且不写去重标记：否则重投会被跳过，该章节将永远得不到审核 */
    @Test
    void failureGoesToDeadLetterWithoutMarkingDone() throws Exception {
        AiAuditMessage message = chapterMessage(9L, 77L);
        doThrow(new IllegalStateException("模型挂了")).when(aiAuditService).auditChapter(9L);

        consumer.onMessage(message, channel, DELIVERY_TAG);

        verify(aiAuditService, times(3)).auditChapter(9L);
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(eq(DELIVERY_TAG), anyBoolean());
        assertTrue(redisKeys.isEmpty(), () -> "失败不该留下去重标记，实际：" + redisKeys);
    }

    /** 整本审核（chapterId 为空）走同一条去重逻辑 */
    @Test
    void wholeNovelAuditIsDeduplicatedToo() throws Exception {
        enableRedis();
        AiAuditMessage message = new AiAuditMessage();
        message.setNovelId(100L);
        message.setOutboxId(88L);

        consumer.onMessage(message, channel, DELIVERY_TAG);
        consumer.onMessage(message, channel, DELIVERY_TAG);

        verify(aiAuditService, times(1)).audit(100L);
        assertTrue(redisKeys.contains("audit:done:88"), () -> "实际：" + redisKeys);
        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
    }
}
