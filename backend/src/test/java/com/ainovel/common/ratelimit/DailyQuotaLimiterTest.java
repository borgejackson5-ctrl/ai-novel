package com.ainovel.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 按日额度限流器单测。
 *
 * <p>需要测试的原因：该计数器直接对应成本。它为平台 AI Key 与文生图设置硬上限，
 * 任一参数写错（key 不带日期 ⇒ 永不跨天清零；TTL 短于一天 ⇒ 当天中途计数消失、额度被重置），
 * 表现都是「服务照常、日志干净、额度形同不存在」。
 *
 * <p>这里用替身而不以 Mockito 桩住 {@code execute}：它的第三个参数是变长参数，
 * 用匹配器断言「传入了哪几个参数」很脆弱；写一个只覆盖用到的两个方法的子类，
 * 断言更直接也更好读。
 *
 * <p>注意：Lua 脚本本身的原子语义（并发下计数不越界）这里覆盖不到，
 * 需要真实 Redis（已在真实 Redis 上以 EVAL 验证）。
 */
@ExtendWith(MockitoExtension.class)
class DailyQuotaLimiterTest {

    private static final String PREFIX = "ai:platform:usage:";
    private static final int LIMIT = 100;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private FakeRedis redis;
    private DailyQuotaLimiter limiter;

    /**
     * 只覆盖限流器实际使用的两个方法：{@code execute(script, keys, args...)} 与 {@code opsForValue()}。
     * 记录每次调用传入的 key 与参数，供断言使用。
     */
    private static class FakeRedis extends StringRedisTemplate {

        final List<String> executedKeys = new ArrayList<>();
        final List<Object> executedArgs = new ArrayList<>();
        private final ValueOperations<String, String> ops;

        /** 模拟脚本返回值：1 = 已占用，0 = 已满 */
        Long scriptReturn = 1L;

        FakeRedis(ValueOperations<String, String> ops) {
            this.ops = ops;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
            executedKeys.addAll(keys);
            executedArgs.addAll(Arrays.asList(args));
            return (T) scriptReturn;
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return ops;
        }
    }

    @BeforeEach
    void setUp() {
        redis = new FakeRedis(valueOperations);
        limiter = new DailyQuotaLimiter(redis);
    }

    private String todayKey() {
        return PREFIX + LocalDate.now();
    }

    // ---------- tryAcquire ----------

    @Test
    @DisplayName("未达上限 → 返回 true，且 key 带当日日期（跨天自动清零）")
    void tryAcquire_underLimit() {
        redis.scriptReturn = 1L;

        assertTrue(limiter.tryAcquire(PREFIX, LIMIT, 1));

        assertEquals(List.of(todayKey()), redis.executedKeys,
                "key 没带日期的话计数永远不清零，额度会一次性用光");
        assertTrue(redis.executedKeys.get(0).endsWith(LocalDate.now().toString()),
                "日期格式应可读（yyyy-MM-dd），便于排查。实际：" + redis.executedKeys.get(0));
    }

    @Test
    @DisplayName("已达上限 → 返回 false，而不是抛异常（调用方据此给用户提示）")
    void tryAcquire_atLimit() {
        redis.scriptReturn = 0L;

        assertFalse(limiter.tryAcquire(PREFIX, LIMIT, 1));
    }

    @Test
    @DisplayName("脚本返回 null 视为未占用（宁可少给一次，也不能当成占用成功）")
    void tryAcquire_nullResult() {
        redis.scriptReturn = null;

        assertFalse(limiter.tryAcquire(PREFIX, LIMIT, 1));
    }

    @Test
    @DisplayName("传给脚本的参数：重量、上限、TTL 三者顺序正确，TTL 不得短于一天")
    void tryAcquire_passesUnitsLimitAndLongEnoughTtl() {
        limiter.tryAcquire(PREFIX, LIMIT, 3000);

        assertEquals(3, redis.executedArgs.size(), "脚本参数个数变了，Lua 里的 ARGV 会错位");
        assertEquals("3000", redis.executedArgs.get(0),
                "本次要扣的重量必须放在第一个参数 —— 放错位置 Lua 会拿它当上限比，额度直接失真");
        assertEquals(String.valueOf(LIMIT), redis.executedArgs.get(1), "上限没原样传给脚本");

        long ttlSeconds = Long.parseLong(String.valueOf(redis.executedArgs.get(2)));
        assertTrue(ttlSeconds >= 86400,
                "TTL 短于一天 ⇒ 计数会在当天中途过期消失，配额被重置（等于不限额）。实际："
                        + ttlSeconds + " 秒");
    }

    @Test
    @DisplayName("重量大于剩余额度 ⇒ 交给脚本判 false（Java 侧不许自己放行，否则额度会被冲穿）")
    void tryAcquire_overRemaining_returnsFalse() {
        // 真实判据在 Lua 中（c + units <= limit）；这里只约束 Java 侧如实采用脚本结果。
        redis.scriptReturn = 0L;

        assertFalse(limiter.tryAcquire(PREFIX, 30000, 3000));
        assertEquals("3000", redis.executedArgs.get(0));
    }

    @Test
    @DisplayName("上限为 0 → 交由脚本判定（配置成 0 表示关掉，而不是放行）")
    void tryAcquire_zeroLimitStillAsksScript() {
        // 这里只断言「上限 0 也交给脚本」，真正的拒绝语义在 Lua 中，已在真实 Redis 上用 EVAL 验证：
        // limit=0 连续三次均返回 0，且不留下任何计数。
        // 早期写法用 `not c` 判断 key 是否存在，会把「限额 0」变成「每天放行一次」（0 < 0 未参与比较）；
        // 修正后先将当前值归一到数字再比较。若在 Java 侧短路返回 true，「关掉」会变成「无限」。
        redis.scriptReturn = 0L;

        assertFalse(limiter.tryAcquire(PREFIX, 0, 1));
        assertEquals("1", redis.executedArgs.get(0), "按次调用重量为 1");
        assertEquals("0", redis.executedArgs.get(1), "上限 0 必须原样传给脚本（不能 Java 侧短路）");
    }

    // ---------- currentUsage ----------

    @Test
    @DisplayName("读当日用量 → 无 key 返回 0")
    void currentUsage_noKey() {
        when(valueOperations.get(todayKey())).thenReturn(null);

        assertEquals(0L, limiter.currentUsage(PREFIX));
    }

    @Test
    @DisplayName("读当日用量 → 正常计数原样返回")
    void currentUsage_normal() {
        when(valueOperations.get(todayKey())).thenReturn("7");

        assertEquals(7L, limiter.currentUsage(PREFIX));
    }

    @Test
    @DisplayName("读当日用量 → 万级计数不截断（额度按字数算，几万是常态）")
    void currentUsage_largeValue() {
        when(valueOperations.get(todayKey())).thenReturn("28500");

        assertEquals(28500L, limiter.currentUsage(PREFIX),
                "按字数计费后计数是千、万级，用 int 接会溢出成负值，界面显示剩余额度直接错乱");
    }

    @Test
    @DisplayName("计数被外部写坏 → 按「没用过」处理，不把异常抛给业务")
    void currentUsage_corruptedValue() {
        when(valueOperations.get(todayKey())).thenReturn("not-a-number");

        assertEquals(0L, limiter.currentUsage(PREFIX),
                "坏值应当降级为 0 —— 为了展示「还剩多少」而让整个接口 500 不值得");
    }

    // ---------- release ----------

    @Test
    @DisplayName("归还额度 → 计数不存在时什么都不做")
    void release_noKey() {
        when(valueOperations.get(todayKey())).thenReturn(null);

        limiter.release(PREFIX, 1);

        verify(valueOperations, never()).decrement(anyString(), anyLong());
    }

    @Test
    @DisplayName("归还额度 → 已经是 0 就不再减（否则会减成负数，把额度放大）")
    void release_zeroStaysZero() {
        when(valueOperations.get(todayKey())).thenReturn("0");

        limiter.release(PREFIX, 1);

        verify(valueOperations, never()).decrement(anyString(), anyLong());
    }

    @Test
    @DisplayName("归还额度 → 按当初扣掉的重量原样退回，不是固定还 1（额度按字数算）")
    void release_returnsExactUnits() {
        when(valueOperations.get(todayKey())).thenReturn("5000");

        limiter.release(PREFIX, 3000);

        verify(valueOperations).decrement(todayKey(), 3000L);
    }

    @Test
    @DisplayName("归还额度 → 计数不足时只减到 0，不能减成负数（负额度会变成「越用越多」）")
    void release_notMoreThanUsed() {
        when(valueOperations.get(todayKey())).thenReturn("100");

        limiter.release(PREFIX, 3000);

        verify(valueOperations).decrement(todayKey(), 100L);
    }

    @Test
    @DisplayName("归还额度 → 重量 ≤ 0 直接返回（不产生一次无意义的 Redis 往返）")
    void release_nonPositiveUnits_doesNothing() {
        limiter.release(PREFIX, 0);

        verify(valueOperations, never()).get(anyString());
        verify(valueOperations, never()).decrement(anyString(), anyLong());
    }

    @Test
    @DisplayName("归还额度 → 计数被写坏时静默跳过，不抛异常")
    void release_corruptedValue() {
        when(valueOperations.get(todayKey())).thenReturn("oops");

        limiter.release(PREFIX, 1);

        verify(valueOperations, never()).decrement(anyString(), anyLong());
    }
}
