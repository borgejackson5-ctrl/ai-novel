package com.ainovel.common.mq;

import com.ainovel.common.message.ChapterChunkSyncMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递者的规则测试：任何失败都记录到表中，不抛给调用方。
 *
 * <p>这是「接口报错但数据已写入」的另一半防线：只要 send 不抛异常，业务接口就不会
 * 因 MQ 不可用而返回失败，消息留在表中等待补投。
 */
@ExtendWith(MockitoExtension.class)
class MqOutboxDispatcherTest {

    private static final long ID = 7L;
    private static final String EXCHANGE = "ai.exchange";
    private static final String ROUTING_KEY = "ai.review";
    /** 能被还原的消息体（类在全项目包名下，JSON 合法） */
    private static final String GOOD_PAYLOAD = "{\"novelId\":1,\"chapterId\":2}";

    @Mock
    private MqOutboxStore outboxStore;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private MqOutboxDispatcher dispatcher;

    @BeforeEach
    void init() {
        dispatcher = new MqOutboxDispatcher(outboxStore, rabbitTemplate, new ObjectMapper());
    }

    private MqOutboxRecord row(String payloadType, String payload, int attempts) {
        return new MqOutboxRecord(ID, EXCHANGE, ROUTING_KEY, payloadType, payload, attempts);
    }

    private MqOutboxRecord goodRow(int attempts) {
        return row(ChapterChunkSyncMessage.class.getName(), GOOD_PAYLOAD, attempts);
    }

    @Test
    @DisplayName("投成功 ⇒ 标记已投；消息体按原类型还原后交给模板（线上格式与首次投递一致）")
    void send_success_marksSent() {
        assertTrue(dispatcher.send(goodRow(0)));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(ROUTING_KEY), payload.capture(),
                any(CorrelationData.class));
        assertTrue(payload.getValue() instanceof ChapterChunkSyncMessage,
                "必须还原成原消息类型再投，否则消费端的 __TypeId__ 对不上");
        verify(outboxStore).markSent(ID);
        verify(outboxStore, never()).markFailed(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("连不上 broker ⇒ 不抛异常（业务接口不能因此失败），记一次失败并退避 10 秒")
    void send_connectFailure_recordsBackoffWithoutThrowing() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused: getsockopt")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        assertDoesNotThrow(() -> dispatcher.send(goodRow(0)));

        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(outboxStore).markFailed(eq(ID), eq(1), next.capture(), error.capture());
        assertTrue(error.getValue().contains("Connection refused"), "失败原因要落库，便于事后查");
        assertTrue(next.getValue().isAfter(LocalDateTime.now()), "重投时间应当在未来（退避生效）");
        verify(outboxStore, never()).markSent(anyLong());
    }

    @Test
    @DisplayName("重试用尽 ⇒ 标记放弃并打 ERROR，不再无限重投（留给人工看）")
    void send_attemptsExhausted_marksDead() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));

        // 默认上限 8 次：已经是第 8 次尝试
        assertFalse(dispatcher.send(goodRow(7)));

        verify(outboxStore).markDead(eq(ID), eq(8), anyString());
        verify(outboxStore, never()).markFailed(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("消息类型不在 com.ainovel 包下 ⇒ 直接放弃，不做反射加载（也不投）")
    void send_typeOutsideWhitelist_marksDeadWithoutSending() {
        assertFalse(dispatcher.send(row("java.lang.RuntimeException", "{}", 0)));

        verify(outboxStore).markDead(eq(ID), anyInt(), anyString());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("消息体坏了（JSON 解析不了）⇒ 放弃，不反复重投同一颗毒丸")
    void send_brokenPayload_marksDead() {
        assertFalse(dispatcher.send(row(ChapterChunkSyncMessage.class.getName(), "{不是 JSON", 0)));

        verify(outboxStore).markDead(eq(ID), anyInt(), anyString());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    }

    @Test
    @DisplayName("退避：10s 起指数增长，封顶 5 分钟（别让断半小时的场景每分钟都刷一次）")
    void backoff_isExponentialAndCapped() {
        assertEquals(10, MqOutboxDispatcher.backoffSeconds(1));
        assertEquals(20, MqOutboxDispatcher.backoffSeconds(2));
        assertEquals(40, MqOutboxDispatcher.backoffSeconds(3));
        assertEquals(160, MqOutboxDispatcher.backoffSeconds(5));
        assertEquals(300, MqOutboxDispatcher.backoffSeconds(6));
        assertEquals(300, MqOutboxDispatcher.backoffSeconds(20));
    }

    /**
     * 「消息已交到 broker」与「标记写入数据库」是两件事，失败必须分开处理。
     *
     * <p>两句必须分开：若共用一个 try，标记失败也会照记 attempts，于是一条实际已经投出的消息
     * 会被一路累加，达到上限后标记为「放弃投递」。运维依据那条 ERROR 去排查「哪条消息未发出」，
     * 而它早已发出：该记录是假的，真正的故障（DB 写入失败）反而被掩盖。
     */
    @Test
    @DisplayName("投出去了但标记失败 ⇒ 仍算投递成功：不记失败、不标放弃（下轮补投会重投，消费端幂等）")
    void send_markFailsAfterSuccessfulSend_notCountedAsDeliveryFailure() {
        doThrow(new IllegalStateException("db down")).when(outboxStore).markSent(ID);

        assertTrue(dispatcher.send(goodRow(0)), "消息已经交到 broker，这次投递就是成功的");

        verify(outboxStore, never()).markFailed(anyLong(), anyInt(), any(), anyString());
        verify(outboxStore, never()).markDead(anyLong(), anyInt(), anyString());
    }
}
