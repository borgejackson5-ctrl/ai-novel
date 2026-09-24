package com.ainovel.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis 的固定窗口限流器：Lua 原子「读-判-增」，计数不越过 limit。
 *
 * <p>与 {@link DailyQuotaLimiter} 的分工：后者管理**一天的总量**（额度，直接对应费用），
 * 本类管理**短时间内的速率**（防刷、防压垮）。两者的 Lua 均不允许写成 GET + INCR 两次往返，
 * 否则并发请求会各自读取同一旧值而同时放行，限流失效。
 *
 * <p><b>窗口起点为该 key 的第一次请求</b>，不对齐自然分钟：TTL 仅在计数由 0 变为 1 时设置一次。
 * 因此窗口长度精确（不会因持续有请求而不断续期、使窗口不断延长），代价是各维度的窗口边界
 * 彼此错开，对限流无影响。
 *
 * <p><b>故障时一律放行（fail-open）</b>，原因有二，均为刻意设计：
 * <ol>
 *   <li>Redis 抖动时抛异常会使**所有**被限流的接口一并返回 500，防护设施反过来导致业务异常，
 *       后果远重于其要防范的问题；</li>
 *   <li>限流配置错误（limit 或 window 非正数）时同理，放行并记录 warn，通过守门测试与告警发现，
 *       而非使线上接口直接不可用。</li>
 * </ol>
 * 主动关闭限流应使用总开关 {@code app.rate-limit.enabled=false}，不应将 limit 写成 0，
 * 后者需要逐个限流点修改且语义含糊。
 *
 * <p><b>不提供「读当前计数」的方法</b>：限流只需判定是否放行。增加读取入口会多出一处可能与真实
 * 计数不一致的地方，且上层获得陈旧计数也无法做出正确决策。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixedWindowRateLimiter {

    /**
     * 读-判-增三步必须在一次 EVAL 内完成。
     *
     * <p>两处细节均为「不做处理即会静默出错」：
     * <ul>
     *   <li>计数被外部写坏（不是数字）时删除后重建，否则紧随其后的 INCR 会直接报错，
     *       使限流反过来导致业务异常；</li>
     *   <li>计数存在却<b>没有过期时间</b>时必须补齐。此类 key 不会自行消失，
     *       一旦计数达到上限，该维度将<b>永久</b>受限、无法恢复。
     *       （{@code ttl} 返回 -1 表示无过期时间，-2 表示 key 不存在。）</li>
     * </ul>
     *
     * <p>被拒绝的那一次<b>不做 INCR</b>，计数停在 limit：如此恢复时刻即等于 key 的到期时刻，
     * 可预测；若继续累加，客户端等待时间会随攻击流量不断延长。
     */
    private static final String FIXED_WINDOW_LUA = """
            local raw = redis.call('get', KEYS[1])
            local current = tonumber(raw or '0')
            if current == nil then
                redis.call('del', KEYS[1])
                current = 0
            end
            if current > 0 and redis.call('ttl', KEYS[1]) < 0 then
                redis.call('expire', KEYS[1], ARGV[2])
            end
            if current >= tonumber(ARGV[1]) then
                return 0
            end
            redis.call('incr', KEYS[1])
            if current == 0 then
                redis.call('expire', KEYS[1], ARGV[2])
            end
            return 1
            """;

    /**
     * 计数器 key 前缀。
     *
     * <p>与 {@link DailyQuotaLimiter} 的 {@code ai:user:usage:} 区分开：在 Redis 中可直接辨别
     * 「这是限流」还是「这是计费」，便于排查。
     */
    static final String KEY_PREFIX = "rl:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 尝试占用一次配额。
     *
     * <p>未提供「不带 window」的重载：重载之间互相委托时，Mockito 中 stub 两参与 stub 三参
     * 是两个独立的方法桩，生产代码调用了未被 stub 的那个不会报错，只会静默返回默认值（false），
     * 表现为「限流全部拒绝」。本项目全部依赖均为 mock，该缺陷必然触发。
     *
     * @param name          限流点名字（同类接口共用一个，与维度拼成 Redis key）
     * @param dimension     限流维度，如 {@code u:10001} 或 {@code ip:1.2.3.4}，由调用方拼好
     * @param limit         窗口内允许的次数；非正数视为「未配置」→ 放行（见类注释的 fail-open）
     * @param windowSeconds 窗口长度（秒）；非正数同上
     * @return true = 放行；false = 超出限额
     */
    public boolean tryAcquire(String name, String dimension, int limit, int windowSeconds) {
        if (limit <= 0 || windowSeconds <= 0) {
            log.warn("限流点 {} 的参数不合法（limit={}, window={}），本次放行。它当前的保护是关着的。",
                    name, limit, windowSeconds);
            return true;
        }
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(FIXED_WINDOW_LUA, Long.class);
            Long ok = stringRedisTemplate.execute(script, List.of(KEY_PREFIX + name + ":" + dimension),
                    String.valueOf(limit), String.valueOf(windowSeconds));
            return ok != null && ok == 1L;
        } catch (Exception e) {
            // Redis 不可达：放行而非拒绝。限流属于防护措施，不应成为单点故障
            log.warn("限流点 {} 计数失败，本次放行（redis 不可用？）: {}", name, e.toString());
            return true;
        }
    }
}
