package com.ainovel.common.mq;

import com.ainovel.common.message.ChapterChunkSyncMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投递出口的规则测试：消息先写入表、再投递，且投递不在业务线程上执行。
 *
 * <p>这里约束的是「接口报错但数据已写入」这一缺陷的防线：
 * 写入表失败必须使调用方失败（业务回滚），写入表成功后投递失败不得影响调用方。
 */
@ExtendWith(MockitoExtension.class)
class MqSenderTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private MqOutboxStore outboxStore;

    @Mock
    private MqOutboxDispatcher outboxDispatcher;

    private MqSender sender;

    private final MqOutboxRecord row =
            new MqOutboxRecord(7L, "ex", "rk", ChapterChunkSyncMessage.class.getName(), "{}", 0);

    @BeforeEach
    void init() {
        sender = new MqSender(rabbitTemplate, outboxStore, outboxDispatcher);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ChapterChunkSyncMessage message() {
        ChapterChunkSyncMessage m = new ChapterChunkSyncMessage();
        m.setNovelId(1L);
        m.setChapterId(2L);
        return m;
    }

    @Test
    @DisplayName("无事务时：先落表再投（消息先持久化，投递只是「顺手试一次」）")
    void sendAfterCommit_noTransaction_savesThenDispatches() {
        when(outboxStore.save(anyString(), anyString(), any())).thenReturn(row);

        sender.sendAfterCommit("ex", "rk", message());

        verify(outboxStore).save("ex", "rk", message());
        verify(outboxDispatcher).dispatchAsync(row);
    }

    @Test
    @DisplayName("事务内：只落表、当场不投；提交之后才投（消费者读不到未提交的数据）")
    void sendAfterCommit_inTransaction_defersUntilCommit() {
        when(outboxStore.save(anyString(), anyString(), any())).thenReturn(row);

        TransactionSynchronizationManager.initSynchronization();
        sender.sendAfterCommit("ex", "rk", message());

        verify(outboxDispatcher, never()).dispatchAsync(any());

        // 模拟事务提交：把注册进去的回调执行一遍
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size(), "应当只注册一个提交后回调");
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(outboxDispatcher).dispatchAsync(row);
    }

    @Test
    @DisplayName("落表失败 ⇒ 异常向上抛（业务事务跟着回滚），且不投递")
    void sendAfterCommit_outboxWriteFails_propagates() {
        when(outboxStore.save(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("MQ 消息序列化失败"));

        assertThrows(IllegalStateException.class, () -> sender.sendAfterCommit("ex", "rk", message()));

        verify(outboxDispatcher, never()).dispatchAsync(any());
    }
}
