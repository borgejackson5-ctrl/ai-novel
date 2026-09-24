package com.ainovel.module.search.task;

import com.ainovel.module.search.domain.vo.ReconcileResultVO;
import com.ainovel.module.search.service.SearchReconcileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索索引对账任务的规则测试。
 *
 * <p>约束三件事：拿不到锁就整段跳过（多实例下不能两个实例同时全量对账）、
 * 正常路径执行完成后释放锁、以及对账内部抛异常也不能漏掉释放锁
 * （漏掉就要等租期自然过期，期间这一小时的对账全部被跳过）。
 */
@ExtendWith(MockitoExtension.class)
class SearchReconcileTaskTest {

    @Mock
    private SearchReconcileService reconcileService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    private SearchReconcileTask task;

    @BeforeEach
    void init() throws Exception {
        task = new SearchReconcileTask(reconcileService, redissonClient);
        // 默认「拿到锁」：锁本身的行为由专门的用例覆盖，其余用例只关心对账逻辑
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().doReturn(true).when(lock).tryLock(anyLong(), any(TimeUnit.class));
        lenient().doReturn(true).when(lock).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("拿不到锁（另一个实例正在对账）⇒ 本轮整段跳过：不查 DB、不修索引")
    void reconcile_lockNotAcquired_skipsEntirely() throws Exception {
        doReturn(false).when(lock).tryLock(anyLong(), any(TimeUnit.class));

        task.reconcile();

        verify(reconcileService, never()).reconcile();
    }

    @Test
    @DisplayName("拿到锁 ⇒ 执行对账，结束后释放锁")
    void reconcile_lockAcquired_runsAndUnlocks() {
        when(reconcileService.reconcile()).thenReturn(new ReconcileResultVO());

        task.reconcile();

        verify(reconcileService).reconcile();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("对账内部抛异常 ⇒ 不外抛（后台任务），但锁必须照样释放")
    void reconcile_serviceThrows_doesNotPropagateAndStillUnlocks() {
        when(reconcileService.reconcile()).thenThrow(new IllegalStateException("ES 挂了"));

        assertDoesNotThrow(() -> task.reconcile());

        verify(lock).unlock();
    }
}
