package com.ainovel.module.ai.service;

import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.mq.MqSender;
import com.ainovel.module.ai.client.AiChatClient;
import com.ainovel.common.message.AiAuditMessage;
import com.ainovel.module.ai.service.impl.AiServiceImpl;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Novel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * AI 审核投递路径测试。
 *
 * <p>约束的是一条约定：全项目 MQ 只有一个出口 {@link MqSender#sendAfterCommit}。
 * 若 {@code submitAudit} 直接调用 {@code rabbitTemplate.convertAndSend}，虽然当前因未加
 * {@code @Transactional} 而顺序恰好正确，但会丢失 publisher-confirm 的 CorrelationData
 * （broker 拒收时无从感知）；并且一旦该方法将来加上 {@code @Transactional}，
 * 就变成「事务尚未提交即发出消息」，消费者回查时可能读不到新状态。
 */
@ExtendWith(MockitoExtension.class)
class AiServiceTest {

    @Mock
    private AiChatClient aiClient;

    @Mock
    private AiConfigService aiConfigService;

    @Mock
    private NovelMapper novelMapper;

    @Mock
    private MqSender mqSender;

    private AiService aiService;

    @BeforeEach
    void initService() {
        aiService = new AiServiceImpl(aiClient, aiConfigService, novelMapper, mqSender);
    }

    @Test
    @DisplayName("提交审核 → 置为待审核状态，并经 sendAfterCommit 投递（不直连 RabbitTemplate）")
    void submitAudit_sendsViaMqSender() {
        aiService.submitAudit(100L);

        // 1) 状态先持久化
        ArgumentCaptor<Novel> novelCaptor = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).updateById(novelCaptor.capture());
        assertEquals(100L, novelCaptor.getValue().getId());
        assertEquals(AuditStatusEnum.WAIT.getCode(), novelCaptor.getValue().getAuditStatus());

        // 2) 消息经统一出口投递，exchange / routingKey / 载荷均正确
        ArgumentCaptor<AiAuditMessage> msgCaptor = ArgumentCaptor.forClass(AiAuditMessage.class);
        verify(mqSender).sendAfterCommit(eq(MqConstant.AI_EXCHANGE),
                eq(MqConstant.AI_AUDIT_ROUTING_KEY), msgCaptor.capture());
        assertEquals(100L, msgCaptor.getValue().getNovelId());
    }

    @Test
    @DisplayName("投递出口自己抛异常时要向上抛（情形只剩「消息没能落表」这类——那时业务必须回滚）")
    void submitAudit_propagatesSendFailure() {
        // 这条约束的不是「broker 连不上」：连不上 broker 已不会抛异常，消息先写入 outbox
        // （与业务数据同一事务），投递在独立线程上执行，失败留在表中等待补投
        // （见 MqSender / MqOutboxDispatcher）。
        // 当前 sendAfterCommit 仍能抛出的，只有「消息写不进去」：序列化失败、库写入失败。
        // 那时必须让整个操作回滚，否则就是「作品已置待审、审核消息永远发不出去」。
        org.mockito.Mockito.doThrow(new RuntimeException("outbox 落表失败"))
                .when(mqSender).sendAfterCommit(any(), any(), any());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> aiService.submitAudit(100L));
    }

    @Test
    @DisplayName("额度口径：只把用户输入交给估算，系统提示词不算进去")
    void estimateUnits_excludesSystemPrompt() {
        org.mockito.Mockito.when(aiConfigService.estimateUnits(any(String.class))).thenReturn(123);

        int units = aiService.estimateUnits("TITLE", "修仙");

        assertEquals(123, units);
        // 这条断言约束的是计费口径：此前会把 SYSTEM_PROMPTS.get(type) 一并传入，
        // 那样「修仙」两个字要按「提示词几十字 + 输入两字」计费。起名/简介被最小计费单位（200 字）
        // 兜着，差额一直看不出来，只能靠测试约束；否则当某个输入真的超过 200 字时，
        // 会出现「输入 300 字却扣了 380 字」的情况。
        verify(aiConfigService).estimateUnits("修仙");
    }

    @Test
    @DisplayName("非法类型直接拒绝，且不碰额度估算（不靠取提示词时的 NPE 兜底）")
    void estimateUnits_rejectsUnknownType() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.ainovel.common.exception.BusinessException.class,
                () -> aiService.estimateUnits("WHAT", "修仙"));

        org.mockito.Mockito.verifyNoInteractions(aiConfigService);
    }
}
