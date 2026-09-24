package com.ainovel.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 固定窗口限流器单测。
 *
 * <p>重点不是验证「计数是否正确」（那需要真实 Redis，见集成测试），而是它失效时的行为：
 * 限流属于防护设施，Redis 抖动或参数配置错误都不应导致接口不可用，
 * 因此这里逐条约束 fail-open。这类分支平时不会执行，一旦执行错误就是整体不可用。
 */
@ExtendWith(MockitoExtension.class)
class FixedWindowRateLimiterTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private FixedWindowRateLimiter limiter;

    @BeforeEach
    void init() {
        limiter = new FixedWindowRateLimiter(stringRedisTemplate);
    }

    @Test
    @DisplayName("脚本返回 1 → 放行；key 带上统一前缀与维度")
    void allowed() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(1L);

        assertTrue(limiter.tryAcquire("novel:detail", "u:10001", 120, 60));

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keys.capture(), anyString(), anyString());
        // 前缀不统一时，Redis 中无法区分哪条计数属于限流、哪条属于计费
        assertEquals(List.of("rl:novel:detail:u:10001"), keys.getValue());
    }

    @Test
    @DisplayName("脚本返回 0 → 拒绝")
    void rejected() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenReturn(0L);

        assertFalse(limiter.tryAcquire("novel:detail", "ip:1.2.3.4", 120, 60));
    }

    @Test
    @DisplayName("Redis 不可用 → 放行（限流不该成为单点故障）")
    void redisDownFailsOpen() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        assertTrue(limiter.tryAcquire("novel:detail", "u:10001", 120, 60),
                "Redis 挂了还拒绝，等于限流把整个服务拖下水");
    }

    @Test
    @DisplayName("限额或窗口非正数 → 放行且不碰 Redis（配置写错不该让接口不可用）")
    void badParametersFailOpen() {
        assertTrue(limiter.tryAcquire("x", "u:1", 0, 60));
        assertTrue(limiter.tryAcquire("x", "u:1", -1, 60));
        assertTrue(limiter.tryAcquire("x", "u:1", 10, 0));

        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString(), anyString());
    }
}
