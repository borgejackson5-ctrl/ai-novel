package com.ainovel.common.mq;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 死信队列的处置动作。
 *
 * <p>重点约束两件事：
 * <ol>
 *   <li><b>队列名必须走白名单</b>——它来自前端，不校验时一个写错的参数就能把业务队列整个清空；</li>
 *   <li><b>任何失败都不能丢消息</b>——取出的消息在投递失败时必须放回死信队列，
 *       最坏结果只是「留在原地，下次再点」。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DlxOpsServiceTest {

    private static final String DLX = MqConstant.SEARCH_SYNC_DLX_QUEUE;

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private DlxOpsService dlxOpsService;

    @BeforeEach
    void setUp() {
        dlxOpsService = new DlxOpsService(amqpAdmin, rabbitTemplate);
    }

    /** 构造一条带死信信息的消息：x-death 记录了它原本来自哪个 exchange、用哪个路由键投递 */
    private static Message deadMessage(String exchange, String routingKey) {
        MessageProperties props = new MessageProperties();
        Map<String, Object> death = new HashMap<>();
        death.put("exchange", exchange);
        death.put("routing-keys", List.of(routingKey));
        death.put("count", 1L);
        props.setHeader("x-death", List.of(death));
        return new Message("{\"novelId\":1}".getBytes(StandardCharsets.UTF_8), props);
    }

    // ================= 白名单 =================

    @Test
    @DisplayName("清空：非死信队列一律拒绝（否则一个写错的队列名就能清空业务队列）")
    void purge_rejectsNonDlxQueue() {
        assertThrows(BusinessException.class, () -> dlxOpsService.purge(MqConstant.SEARCH_CHUNK_QUEUE));
        verify(amqpAdmin, never()).purgeQueue(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("重放：非死信队列一律拒绝")
    void replay_rejectsNonDlxQueue() {
        assertThrows(BusinessException.class, () -> dlxOpsService.replay(MqConstant.SEARCH_SYNC_QUEUE));
        verify(rabbitTemplate, never()).receive(anyString());
    }

    @Test
    @DisplayName("队列名为 null 也不放行")
    void purge_rejectsNullQueue() {
        assertThrows(BusinessException.class, () -> dlxOpsService.purge(null));
    }

    // ================= 重放 =================

    @Test
    @DisplayName("重放：按 x-death 里的 exchange / routing key 把消息投回原位置")
    void replay_sendsBackToOriginalRoute() {
        Message message = deadMessage(MqConstant.SEARCH_EXCHANGE, MqConstant.SEARCH_SYNC_ROUTING_KEY);
        when(rabbitTemplate.receive(DLX)).thenReturn(message, (Message) null);

        DlxOpsService.ReplayResult result = dlxOpsService.replay(DLX);

        assertEquals(1, result.replayed(), "应重放 1 条");
        assertEquals(0, result.failed());
        verify(rabbitTemplate).send(
                eq(MqConstant.SEARCH_EXCHANGE), eq(MqConstant.SEARCH_SYNC_ROUTING_KEY), eq(message));
    }

    @Test
    @DisplayName("重放：缺 x-death 的消息无法判断去向 → 放回原队列并计入跳过")
    void replay_withoutDeathInfo_requeuesAndSkips() {
        Message noDeath = new Message("x".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        when(rabbitTemplate.receive(DLX)).thenReturn(noDeath, (Message) null);

        DlxOpsService.ReplayResult result = dlxOpsService.replay(DLX);

        assertEquals(0, result.replayed());
        assertEquals(1, result.skipped());
        // 放回用「默认 exchange + 队列名」：等价于直接投递回该队列
        verify(rabbitTemplate).send(eq(""), eq(DLX), eq(noDeath));
    }

    @Test
    @DisplayName("重放：投递失败必须把消息放回死信队列（宁可留在原地，不能丢）")
    void replay_sendFails_requeues() {
        Message message = deadMessage(MqConstant.SEARCH_EXCHANGE, MqConstant.SEARCH_SYNC_ROUTING_KEY);
        when(rabbitTemplate.receive(DLX)).thenReturn(message, (Message) null);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker 抖动"))
                .when(rabbitTemplate).send(
                        eq(MqConstant.SEARCH_EXCHANGE), eq(MqConstant.SEARCH_SYNC_ROUTING_KEY), any(Message.class));

        DlxOpsService.ReplayResult result = dlxOpsService.replay(DLX);

        assertEquals(0, result.replayed());
        assertEquals(1, result.failed());
        // 关键判据：失败后仍要放回，否则消息随确认一起丢掉
        verify(rabbitTemplate).send(eq(""), eq(DLX), eq(message));
    }

    @Test
    @DisplayName("重放：队列为空时立即返回，不产生任何投递")
    void replay_emptyQueue_noop() {
        when(rabbitTemplate.receive(DLX)).thenReturn(null);

        DlxOpsService.ReplayResult result = dlxOpsService.replay(DLX);

        assertEquals(0, result.replayed());
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    @DisplayName("重放：先整批取出再处理 —— 被放回的消息不会被同一轮再次取到")
    void replay_drainsBatchBeforeProcessing() {
        Message first = deadMessage(MqConstant.SEARCH_EXCHANGE, MqConstant.SEARCH_SYNC_ROUTING_KEY);
        Message second = deadMessage(MqConstant.SEARCH_EXCHANGE, MqConstant.SEARCH_SYNC_ROUTING_KEY);
        when(rabbitTemplate.receive(DLX)).thenReturn(first, second, (Message) null);

        DlxOpsService.ReplayResult result = dlxOpsService.replay(DLX);

        assertEquals(2, result.replayed());
        // 恰好三次：取第一条、取第二条、取到 null 结束；处理阶段不再取
        verify(rabbitTemplate, times(3)).receive(DLX);
    }

    // ================= 清空 =================

    @Test
    @DisplayName("清空：先读条数再执行，返回值供界面提示「丢弃了多少」")
    void purge_reportsDroppedCount() {
        when(amqpAdmin.getQueueInfo(DLX)).thenReturn(new QueueInformation(DLX, 3, 0));

        int dropped = dlxOpsService.purge(DLX);

        assertEquals(3, dropped);
        verify(amqpAdmin).purgeQueue(DLX, false);
    }

    @Test
    @DisplayName("清空：队列不存在（读不到信息）时返回 0，且不报错")
    void purge_queueMissing_returnsZero() {
        when(amqpAdmin.getQueueInfo(DLX)).thenReturn(null);

        assertEquals(0, dlxOpsService.purge(DLX));
        assertTrue(true);
    }
}
