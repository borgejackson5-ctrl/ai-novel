package com.ainovel.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 业务指标门面（Micrometer → {@code /actuator/prometheus}）。
 *
 * <p><b>与自研 {@code MetricsCollector} 的分工</b>：后者是供后台页面查看「哪几个接口慢」的
 * 内存快照，重启即失效，也无法接入监控体系；此处输出的是**标准指标**，可被 Prometheus
 * 抓取、可配置告警、可观察趋势。两者不重复，各司其职。
 *
 * <p><b>仅埋点「异常时需要第一时间发现」的指标</b>，不追求覆盖面：降级率（缓慢上升时
 * 用户仅感觉功能变差，而日志中无异常记录）、缓存命中（下降即为回源风暴的前兆）、
 * SSE 收尾结果（失败率是流式接口唯一的健康信号）、outbox 积压（MQ 故障的第一现场）。
 *
 * <p>命名遵循 Micrometer 约定（点分小写），导出时自动转为下划线：
 * {@code ai.call} → {@code ai_call_total}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BusinessMetrics {

    /** 指标名前缀，避免与第三方库的指标重名 */
    private static final String PREFIX = "ainovel.";

    private final MeterRegistry registry;

    // ==================== AI ====================

    /**
     * 记录一次**调用成功**的 AI 调用（失败的调用不计入，由 {@link #aiDegrade} 记录）。
     *
     * @param scene  调用场景（TITLE / INTRO / CONTINUE / POLISH / REVIEW / SEARCH…）
     * @param model  实际使用的模型名
     * @param costMs 耗时
     */
    public void aiCall(String scene, String model, long costMs) {
        if (!increment("ai.call", "scene", scene, "model", model)) {
            return;
        }
        try {
            Timer timer = registry.timer(PREFIX + "ai.call.duration", "scene", scene);
            timer.record(Duration.ofMillis(Math.max(0, costMs)));
        } catch (Exception e) {
            log.debug("指标埋点失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 记录一次因「AI 不可用」导致的降级。
     *
     * <p>使用独立的 counter 而非仅在 {@code ai.call} 上加 tag：降级率是需要观察趋势的指标，
     * 其缓慢上升时用户仅感觉功能变差，而日志中无任何 ERROR 记录。
     *
     * @param reason no_key / failed / timeout / local_fallback
     */
    public void aiDegrade(String scene, String reason) {
        increment("ai.degrade", "scene", scene, "reason", reason);
    }

    // ==================== 缓存 ====================

    /**
     * 记一次缓存读取。
     *
     * @param cache 缓存名（如 novel_detail / chapter_content / rank）
     */
    public void cacheAccess(String cache, boolean hit) {
        increment("cache.access", "cache", cache, "result", hit ? "hit" : "miss");
    }

    // ==================== 流式接口 ====================

    /**
     * 记录一次流式请求的收尾结果。
     *
     * <p>该指标是流式接口「成功」的唯一信号：响应码在开流时即已发出（200），
     * 之后无论正常结束、被用户中断还是超时，HTTP 层面均无区别。
     * 因此收尾结果必须单独记录，否则流失败率无从观测。
     *
     * @param api     入口名（generate / continue / polish）
     * @param outcome done（执行完毕并正常收尾）/ cancelled（用户主动停止或客户端断开）/
     *                timeout（SSE 超时，与 cancelled 的区别在于该档需退还额度）/ error
     */
    public void sseStream(String api, String outcome) {
        increment("sse.stream", "api", api, "outcome", outcome);
    }

    // ==================== 消息队列 ====================

    /**
     * 将「未投递的 outbox 条数」注册为 Gauge。
     *
     * <p>使用 Gauge 而非定时 increment：积压量是**瞬时值**而非累计量，
     * Prometheus 每次抓取时实时查询当前未投出的条数，才是「此刻的积压量」。
     *
     * @param supplier 实时查询积压条数（内部应自带异常兜底，避免抓取失败）
     */
    public void registerOutboxBacklog(java.util.function.Supplier<Number> supplier) {
        try {
            // 对 supplier 再包一层：读取该 Gauge 的时机正是 MQ 出现问题时，
            // 此时 supplier 很可能抛异常（DB 抖动等），异常传播到抓取端会使整个
            // /actuator/prometheus 不可用，即最需要指标时指标失效。
            // 查询失败返回 -1：0 表示「一切正常」，显示为 0 会产生误导
            java.util.function.Supplier<Number> safe = () -> {
                try {
                    Number value = supplier.get();
                    return value == null ? -1 : value;
                } catch (Exception e) {
                    return -1;
                }
            };
            // 使用 (name, Supplier) 重载，不使用 (name, obj, valueFunction)：
            // 后者对 obj 为**弱引用**，本地 lambda 注册后即无强引用，GC 执行后指标变为 NaN
            // （曾观察到 /actuator/prometheus 中该项为 ainovel_mq_outbox_backlog NaN）
            io.micrometer.core.instrument.Gauge
                    .builder(PREFIX + "mq.outbox.backlog", safe)
                    .description("还没投递出去的 outbox 消息条数（持续不为 0 说明 MQ 投递有问题）")
                    .register(registry);
        } catch (Exception e) {
            log.warn("注册 outbox 积压指标失败（不影响业务）: {}", e.getMessage());
        }
    }

    /**
     * 将「死信队列内的消息条数（7 个队列合计）」注册为 Gauge。
     *
     * <p>该指标的必要性：死信队列**只留痕、不自动消费**，只进不出。缺少指标时，
     * 「某一类消息持续失败」在系统中**没有任何地方会体现**：接口正常返回、
     * 日志中仅有消费端若干 warn、队列中的消息不会被主动查看。消息将持续堆积，
     * 直至耗尽 broker 的内存或磁盘，此时 broker 转入 blocked 状态，
     * **所有生产者一并被阻塞**，整个业务无法发出消息。该曲线是这一情况的唯一提前信号。
     *
     * <p>正常情况下恒为 0；持续大于 0 表示存在未被成功处理的消息，需排查类别。
     *
     * @param supplier 实时查询积压条数（内部自带异常兜底；所有队列均查询失败时应返回 -1 而非 0）
     */
    public void registerDlxBacklog(java.util.function.Supplier<Number> supplier) {
        try {
            java.util.function.Supplier<Number> safe = () -> {
                try {
                    Number value = supplier.get();
                    return value == null ? -1 : value;
                } catch (Exception e) {
                    return -1;
                }
            };
            // 与 outbox 积压同理：必须使用 (name, Supplier) 重载，
            // (name, obj, valueFn) 对 obj 为弱引用，GC 执行后指标变为 NaN
            io.micrometer.core.instrument.Gauge
                    .builder(PREFIX + "mq.dlx.backlog", safe)
                    .description("死信队列里的消息条数合计（恒为 0 才正常；持续大于 0 说明有消息反复失败）")
                    .register(registry);
        } catch (Exception e) {
            log.warn("注册死信队列积压指标失败（不影响业务）: {}", e.getMessage());
        }
    }

    // ==================== 内部 ====================

    /** 埋点失败不得影响业务，这是所有指标埋点的首要原则 */
    private boolean increment(String name, String... tags) {
        try {
            Counter counter = registry.counter(PREFIX + name, tags);
            counter.increment();
            return true;
        } catch (Exception e) {
            log.debug("指标埋点失败（不影响业务）: {}", e.getMessage());
            return false;
        }
    }
}
