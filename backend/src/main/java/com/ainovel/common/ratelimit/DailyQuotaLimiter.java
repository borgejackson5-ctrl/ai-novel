package com.ainovel.common.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 基于 Redis 的按日硬上限限流器：Lua 原子「读-判-增」，计数不越过 limit。
 *
 * <p>适用于「每日总量有硬上限」的场景：
 * <ul>
 *   <li>平台 AI Key 的调用次数（{@code ai:platform:usage:*}，每次重量 1）；</li>
 *   <li>用户免费额度，<b>按字数</b>计（{@code ai:user:usage:*}，每次重量 = 本次送入模型的字数）。</li>
 * </ul>
 * key 按当日日期滚动（前缀 + LocalDate），跨天自动清零。
 *
 * <p><b>重量（units）与上限使用同一口径</b>：上限 30000 表示当天总计可用 30000 字，
 * 单次扣减量由调用方计算后传入。因此同一计数器既可按次数、也可按字数计费，
 * 无需为两种口径各维护一份脚本。
 *
 * <p><b>「今天」所使用的时区</b>由 {@link #billingZone} 指定，默认 {@code Asia/Shanghai}，
 * 不跟随 JVM 默认时区。
 *
 * <p>当前部署形态下 JVM 时区即为东八区：后端镜像将
 * {@code /usr/share/zoneinfo/Asia/Shanghai} 复制到 {@code /etc/localtime}（见 backend/Dockerfile），
 * compose 亦显式指定 {@code TZ: Asia/Shanghai}。因此此处并非线上正在发生的缺陷，
 * 而是一处加固：上述两处保证均属于**运行环境**，而「额度按自然日重置」属于**业务口径**。
 * 直接以 {@code java -jar} 运行在未配置时区的机器上时（{@code eclipse-temurin:21-jre-alpine}
 * 未设置 TZ 时 {@code TimeZone.getDefault()} 返回 GMT），「今天」的边界将前移至北京时间早上 8 点：
 * 用户 23:00 用完额度，凌晨 0:30 仍被告知当天免费字数已用完，8 点后额度恢复。
 * 将时区写入配置后，该口径不再由宿主机决定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyQuotaLimiter {

    /**
     * 读-判-增三步必须在一次 EVAL 内完成。
     *
     * <p>注意第一行：**不能**使用 {@code not c} 判断"key 不存在"。不存在的 key 会被视为
     * "尚未使用"而放行一次，导致**上限配置为 0 时反而每天免费发放一次**：运维将上限改为 0
     * 的意图是"关闭"，实际语义却成为"每天一次"。因此先将当前值归一到数字再比较：
     * {@code 0 + units <= 0} 为假，即 0 表示完全不发放。该行为已在真实 Redis 上通过 EVAL 验证。
     *
     * <p>判据为 {@code c + units <= limit}，<b>不是</b> {@code c < limit}：引入重量后，
     * 「已用 29000、本次需扣 3000」必须判定为"不足"，使用后一种写法会放行，
     * 额度将被单次大请求击穿。
     *
     * <p>计数被外部写坏时删除后重建：否则脏值会使紧随其后的 INCRBY 直接报错，
     * 使额度校验反过来导致业务异常。口径与 {@link #currentUsage} 的"宁可多给一次也不报错"一致。
     *
     * <p>原子性由该脚本在 Redis 内单线程执行保证。若拆成 GET + INCR 两次往返，并发下会各自读取
     * 同一旧值而同时放行；以 50 线程对照验证，超发确实存在。
     */
    private static final String ACQUIRE_UNDER_LIMIT_LUA = """
            local raw = redis.call('get', KEYS[1])
            local c = tonumber(raw or '0')
            if c == nil then
                redis.call('del', KEYS[1])
                c = 0
            end
            local units = tonumber(ARGV[1])
            if (c + units <= tonumber(ARGV[2])) then
                redis.call('incrby', KEYS[1], units)
                redis.call('expire', KEYS[1], ARGV[3])
                return 1
            end
            return 0
            """;

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 计费口径的时区（默认东八区，理由见类注释）。
     *
     * <p>字段自带默认值：无 Spring 上下文的单测中 {@code @Value} 不会被注入，
     * 此时仍为东八区，不会退化为「UTC 的今天」。
     */
    @Value("${app.billing-zone:Asia/Shanghai}")
    private String billingZone = "Asia/Shanghai";

    /**
     * 解析计费时区；配置非法时退回东八区并记录 warn。
     *
     * <p>不直接抛出：额度是所有 AI 功能的必经路径，一个写错的配置项不应导致整个 AI 模块 500。
     * 判据与 {@link FixedWindowRateLimiter} 对非法参数的处理一致，即防护设施不引入新的故障点。
     */
    private ZoneId zone() {
        try {
            return ZoneId.of(billingZone);
        } catch (Exception e) {
            log.warn("计费时区配置不合法（app.billing-zone={}），本次按 Asia/Shanghai 处理", billingZone);
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /**
     * 按重量占用当日额度，如「本次消耗 3000 字」。
     *
     * <p>仅按次数计费的场景（平台调用上限、文生图）重量传 {@code 1}。<b>未提供两参重载</b>：
     * 重载之间互相委托时，Mockito 中 stub 两参与 stub 三参是两个独立的方法桩，
     * 生产代码调用了未被 stub 的那个不会报错，只会静默返回默认值（false），
     * 表现为「额度判断失效」。本项目全部依赖均为 mock，该缺陷必然触发，因此仅保留一个方法。
     *
     * @param keyPrefix 计数器 key 前缀，跨天自动清零
     * @param limit     每日硬上限，与 units 同口径
     * @param units     本次要消耗的重量（按次计费传 1），须为正
     * @return true = 占用成功；false = 剩余不足本次消耗（<b>不会</b>部分占用）
     */
    public boolean tryAcquire(String keyPrefix, long limit, long units) {
        String key = keyPrefix + LocalDate.now(zone());
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(ACQUIRE_UNDER_LIMIT_LUA, Long.class);
        Long ok = stringRedisTemplate.execute(script, List.of(key),
                String.valueOf(units), String.valueOf(limit),
                String.valueOf(Duration.ofDays(2).getSeconds()));
        return ok != null && ok == 1L;
    }

    /**
     * 读取某计数器「当日」已消耗的重量（只读，不占用额度）。
     *
     * <p>用于前端展示「今日剩余量」：剩余 = 上限 - 本方法返回值。
     * 计数 key 跨天即失效，因此此处天然只会读到当日用量，无需额外的日期字段。
     *
     * @param keyPrefix 计数器 key 前缀，与 {@link #tryAcquire} 传入的必须一致
     * @return 当日已用量；当日未使用或 key 已过期返回 0
     */
    public long currentUsage(String keyPrefix) {
        String value = stringRedisTemplate.opsForValue().get(keyPrefix + LocalDate.now(zone()));
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 计数被外部写坏时不阻断业务，按「未使用」处理（宁可多给用户一次，也不产生报错）
            return 0;
        }
    }

    /**
     * 按重量归还当日额度。
     *
     * <p>最多减至 0：计数不存在或余额不足时按实际值减，避免减为负数而放大额度。
     * 与 {@link #tryAcquire} 之间非严格原子（并发下有极小概率多归还一次），
     * 但免费额度宁可多给一次，也不应让用户为一次失败的调用付费。
     *
     * @param keyPrefix 计数器 key 前缀，与 {@link #tryAcquire} 传入的必须一致
     * @param units     要归还的重量（调用方传入当初扣减的数量）
     */
    public void release(String keyPrefix, long units) {
        if (units <= 0) {
            return;
        }
        String key = keyPrefix + LocalDate.now(zone());
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return;
        }
        try {
            long back = Math.min(Long.parseLong(value), units);
            if (back > 0) {
                stringRedisTemplate.opsForValue().decrement(key, back);
            }
        } catch (NumberFormatException e) {
            // 计数被外部写坏时不修改，避免异常传播到业务链路
        }
    }
}
