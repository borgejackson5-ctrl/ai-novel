package com.ainovel.common.mq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补投任务的规则测试：该任务本身就是「消息不会丢失」这一承诺的实现。
 *
 * <p>约束四件事：待投的逐条尝试投递；单条投递失败不能影响整轮；
 * 表访问出错也不向外抛（属于后台任务，抛出只会让调度线程记日志，下一轮仍需继续执行）；
 * 以及同一时刻只允许一个实例在投递（多实例下两个实例会取到同一批记录）。
 */
@ExtendWith(MockitoExtension.class)
class MqOutboxResendTaskTest {

    @Mock
    private MqOutboxStore outboxStore;

    @Mock
    private MqOutboxDispatcher dispatcher;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private MqOutboxResendTask task;

    @BeforeEach
    void init() throws Exception {
        task = new MqOutboxResendTask(outboxStore, dispatcher, redissonClient);
        // 默认「拿到锁」：锁自身的行为由专门的用例覆盖，其余用例只关注补投逻辑。
        // lenient：并非每个用例都会执行取锁的代码（例如「拿不到锁」那条会自行覆盖该桩）
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().doReturn(true).when(lock).tryLock(anyLong(), any(TimeUnit.class));
        lenient().doReturn(true).when(lock).isHeldByCurrentThread();
    }

    private MqOutboxRecord row(long id) {
        return new MqOutboxRecord(id, "ex", "rk", "com.ainovel.X", "{}", 1);
    }

    @Test
    @DisplayName("有待投 ⇒ 逐条试着投，并顺手清理已投记录")
    void resend_dispatchesEveryDueRow() {
        when(outboxStore.findDue(anyInt())).thenReturn(List.of(row(1L), row(2L)));

        task.resend();

        verify(dispatcher).send(row(1L));
        verify(dispatcher).send(row(2L));
        verify(outboxStore).purgeSent();
    }

    @Test
    @DisplayName("没有待投 ⇒ 一条都不投（正常情况就该是这样，别做无用的库查询以外的事）")
    void resend_nothingDue_doesNothing() {
        when(outboxStore.findDue(anyInt())).thenReturn(List.of());

        task.resend();

        verify(dispatcher, never()).send(any());
        verify(outboxStore).purgeSent();
    }

    @Test
    @DisplayName("查表就失败了 ⇒ 不外抛（后台任务，下一轮还得继续跑）")
    void resend_storeFailure_doesNotPropagate() {
        when(outboxStore.findDue(anyInt())).thenThrow(new IllegalStateException("库连不上"));

        assertDoesNotThrow(() -> task.resend());

        verify(dispatcher, never()).send(any());
        verify(outboxStore, never()).markFailed(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("拿不到锁（另一个实例正在投）⇒ 本轮整段跳过：不查表、不投、不清表")
    void resend_lockNotAcquired_skipsEntirely() throws Exception {
        doReturn(false).when(lock).tryLock(anyLong(), any(TimeUnit.class));

        task.resend();

        // 关键在「不查表」：仅跳过投递不够，两个实例各自执行 findDue 才是重复投递的根源
        verify(outboxStore, never()).findDue(anyInt());
        verify(dispatcher, never()).send(any());
        verify(outboxStore, never()).purgeSent();
    }
}
