package com.ainovel.common.util;

import com.ainovel.common.metrics.BusinessMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 缓存助手单测：验证缓存穿透 / 击穿 / 雪崩三类防御的核心行为。
 *
 * <ul>
 *   <li>命中：读缓存直接返回，不加锁、不回源</li>
 *   <li>穿透：回源为 null 时写空标记，后续命中直接返回 null</li>
 *   <li>击穿：未命中加互斥锁 + double-check，仅单线程回源；拿不到锁时降级直查</li>
 *   <li>雪崩：写缓存时 TTL 带随机抖动（用 Duration 断言存在性，抖动范围由实现保证）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CacheHelperTest {

    private static final TypeReference<Long> LONG_TYPE = new TypeReference<>() {
    };

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private CacheHelper cacheHelper;

    @BeforeEach
    void setUp() {
        cacheHelper = new CacheHelper(stringRedisTemplate, redissonClient,
                new BusinessMetrics(new SimpleMeterRegistry()));
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @Test
    @DisplayName("缓存命中 → 直接返回，不打锁、不回源")
    void get_cacheHit() {
        when(valueOps.get("k")).thenReturn("123");

        AtomicInteger loaderCalls = new AtomicInteger();
        Long result = cacheHelper.get("k", LONG_TYPE,
                () -> loaderCalls.incrementAndGet() * 0L + 123L, 30, TimeUnit.MINUTES);

        assertEquals(123L, result);
        assertEquals(0, loaderCalls.get());
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("穿透防护：空值标记命中 → 返回 null，不回源")
    void get_nullFlagHit() {
        when(valueOps.get("k")).thenReturn("__NULL__");

        AtomicInteger loaderCalls = new AtomicInteger();
        Long result = cacheHelper.get("k", LONG_TYPE,
                () -> loaderCalls.incrementAndGet() * 0L + 123L, 30, TimeUnit.MINUTES);

        assertNull(result);
        assertEquals(0, loaderCalls.get());
    }

    @Test
    @DisplayName("未命中 + 拿到锁 → 单线程回源并写缓存")
    void get_miss_lockAndLoad() throws InterruptedException {
        when(valueOps.get("k")).thenReturn(null);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);

        Long result = cacheHelper.get("k", LONG_TYPE, () -> 123L, 30, TimeUnit.MINUTES);

        assertEquals(123L, result);
        // 回源结果写入缓存（带随机抖动 TTL，此处只断言 value 正确、TTL 非空）
        verify(valueOps).set(eq("k"), eq("123"), any(Duration.class));
    }

    @Test
    @DisplayName("穿透防护：回源为 null → 写空标记并返回 null")
    void get_miss_loadNull_setsNullFlag() throws InterruptedException {
        when(valueOps.get("k")).thenReturn(null);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(true);

        Long result = cacheHelper.get("k", LONG_TYPE, () -> null, 30, TimeUnit.MINUTES);

        assertNull(result);
        verify(valueOps).set(eq("k"), eq("__NULL__"), any(Duration.class));
    }

    @Test
    @DisplayName("击穿防护：拿不到锁 → 降级直接回源，不写缓存")
    void get_lockFail_fallbackToLoader() throws InterruptedException {
        when(valueOps.get("k")).thenReturn(null);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(false);

        Long result = cacheHelper.get("k", LONG_TYPE, () -> 123L, 30, TimeUnit.MINUTES);

        assertEquals(123L, result);
        // 降级路径不写缓存（避免无意义的写入，并保证可用性）
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("击穿防护：拿不到锁、但等锁期间缓存已被重建 → 用缓存，不再回源")
    void get_lockFail_cacheFilledMeanwhile_reusesCache() throws InterruptedException {
        // 第一次读（未命中）返回 null；等待锁之后再读一次，取得持锁线程写入的值
        when(valueOps.get("k")).thenReturn(null, "456");
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(false);

        AtomicInteger loads = new AtomicInteger();
        Long result = cacheHelper.get("k", LONG_TYPE,
                () -> {
                    loads.incrementAndGet();
                    return 123L;
                }, 30, TimeUnit.MINUTES);

        assertEquals(456L, result);
        // 关键断言：拿不到锁时若不再次读取缓存，热点 key 过期会让所有并发请求同时查库
        assertEquals(0, loads.get(), "缓存已经重建好了，不该再查一次库");
    }

    @Test
    @DisplayName("主动失效 → 删除 key")
    void evict_deletesKey() {
        cacheHelper.evict("novel:detail:100");

        verify(stringRedisTemplate).delete("novel:detail:100");
    }

    @Test
    @DisplayName("无事务时 evictAfterCommit 立即删（与 evict 等价）")
    void evictAfterCommit_noTransaction_evictsImmediately() {
        cacheHelper.evictAfterCommit("novel:detail:100");

        verify(stringRedisTemplate).delete("novel:detail:100");
    }

    @Test
    @DisplayName("有事务时 evictAfterCommit 必须等提交后才删 —— 事务内就删会让并发读把旧值回填进缓存")
    void evictAfterCommit_inTransaction_defersUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            cacheHelper.evictAfterCommit("novel:detail:100");

            // 关键断言：已注册同步回调，但此刻不能已经删除
            verify(stringRedisTemplate, never()).delete("novel:detail:100");
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

            // 模拟提交 → 此时才删除
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(stringRedisTemplate).delete("novel:detail:100");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("事务回滚时不删缓存（数据没变，缓存也没脏）")
    void evictAfterCommit_rollback_doesNotEvict() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            cacheHelper.evictAfterCommit("novel:detail:100");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(stringRedisTemplate, never()).delete("novel:detail:100");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
