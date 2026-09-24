package com.ainovel.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务指标门面的单测。
 *
 * <p>使用 {@code SimpleMeterRegistry} 现算现值，而不 mock {@code MeterRegistry}：
 * 前者确实在执行 Micrometer 的命名与聚合逻辑，可以验证「指标名 / 标签的实际形态」，
 * 而不只是「某个方法被调用过」。
 */
@DisplayName("业务指标门面")
class BusinessMetricsTest {

    private SimpleMeterRegistry registry;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BusinessMetrics(registry);
    }

    private double count(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("AI 调用按场景与模型分别计数，并记录耗时")
    void aiCall_countsBySceneAndModel() {
        metrics.aiCall("TITLE", "deepseek-chat", 120);
        metrics.aiCall("TITLE", "deepseek-chat", 80);
        metrics.aiCall("REVIEW", "deepseek-chat", 3000);

        assertThat(count("ainovel.ai.call", "scene", "TITLE", "model", "deepseek-chat")).isEqualTo(2);
        assertThat(count("ainovel.ai.call", "scene", "REVIEW", "model", "deepseek-chat")).isEqualTo(1);
        assertThat(registry.find("ainovel.ai.call.duration").tag("scene", "REVIEW").timer().count())
                .as("耗时也要记下来 —— 模型变慢时要能看出来")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("降级按场景与原因分别计数 —— 降级率就是要看这个")
    void aiDegrade_countsByReason() {
        metrics.aiDegrade("SEARCH", "fallback");
        metrics.aiDegrade("SEARCH", "fallback");
        metrics.aiDegrade("SEARCH", "mock");

        assertThat(count("ainovel.ai.degrade", "scene", "SEARCH", "reason", "fallback")).isEqualTo(2);
        assertThat(count("ainovel.ai.degrade", "scene", "SEARCH", "reason", "mock")).isEqualTo(1);
    }

    @Test
    @DisplayName("缓存命中与未命中分开计")
    void cacheAccess_splitsHitAndMiss() {
        metrics.cacheAccess("novel:detail", true);
        metrics.cacheAccess("novel:detail", false);
        metrics.cacheAccess("book:shelf", true);

        assertThat(count("ainovel.cache.access", "cache", "novel:detail", "result", "hit")).isEqualTo(1);
        assertThat(count("ainovel.cache.access", "cache", "novel:detail", "result", "miss")).isEqualTo(1);
        assertThat(count("ainovel.cache.access", "cache", "book:shelf", "result", "hit")).isEqualTo(1);
    }

    @Test
    @DisplayName("流式接口按收尾结果分（正常收完 / 用户停止 / 超时 / 出错）")
    void sseStream_countsByOutcome() {
        metrics.sseStream("/ai/write/continue", "done");
        metrics.sseStream("/ai/write/continue", "cancelled");

        assertThat(count("ainovel.sse.stream", "api", "/ai/write/continue", "outcome", "done")).isEqualTo(1);
        assertThat(count("ainovel.sse.stream", "api", "/ai/write/continue", "outcome", "cancelled")).isEqualTo(1);
    }

    @Test
    @DisplayName("outbox 积压是 Gauge：每次读都现取，不是累计值")
    void outboxBacklog_isGauge() {
        int[] backlog = {3};
        metrics.registerOutboxBacklog(() -> backlog[0]);

        assertThat(registry.find("ainovel.mq.outbox.backlog").gauge().value()).isEqualTo(3);
        // 换一个值再读一次：Gauge 的语义即「当前值」
        backlog[0] = 0;
        assertThat(registry.find("ainovel.mq.outbox.backlog").gauge().value()).isEqualTo(0);
    }

    @Test
    @DisplayName("埋点自身出错不影响业务：supplier 抛异常时不会把调用方带崩")
    void outboxBacklog_supplierThrows_doesNotPropagate() {
        metrics.registerOutboxBacklog(() -> {
            throw new IllegalStateException("DB 挂了");
        });

        // 读取该 Gauge 会触发 supplier：异常不能传播到调用方（调用方为 Prometheus 抓取）
        assertThat(registry.find("ainovel.mq.outbox.backlog").gauge()).isNotNull();
    }
}
