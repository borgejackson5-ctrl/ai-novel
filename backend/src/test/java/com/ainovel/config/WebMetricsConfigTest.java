package com.ainovel.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code WebMetricsConfig} 中 HTTP 指标过滤规则的守门测试。
 *
 * <p>需要单独测试一条过滤规则的原因：它是静默的。写宽了不会有人发现
 * （抓取端的请求混进业务指标、把 p99 拉偏），写窄了同样不会有人发现
 * （真正需要排查时业务接口全被过滤掉）。两个方向都不报错，只能靠这条判据约束。
 */
@DisplayName("HTTP 指标过滤：actuator 的请求不进统计")
class WebMetricsConfigTest {

    /** 只使用该判据，无需装配 Bean */
    private final MeterFilter filter = new WebMetricsConfig(null).denyActuatorHttpMetrics();

    private static Meter.Id httpId(String uri) {
        return new Meter.Id("http.server.requests", Tags.of("uri", uri), null, null, Meter.Type.COUNTER);
    }

    @Test
    @DisplayName("抓取端打的 /actuator/** 被排除")
    void actuatorDenied() {
        assertEquals(MeterFilterReply.DENY, filter.accept(httpId("/actuator/prometheus")));
        assertEquals(MeterFilterReply.DENY, filter.accept(httpId("/actuator/health")));
    }

    @Test
    @DisplayName("/actuator 前缀相近但真的是业务路径的，不受影响")
    void lookalikeBusinessPathKept() {
        // 只按前缀排除，不使用 contains：该路径名中包含 actuator 字样
        assertEquals(MeterFilterReply.NEUTRAL, filter.accept(httpId("/actuators-report")));
        assertEquals(MeterFilterReply.NEUTRAL, filter.accept(httpId("/novel/page")));
    }

    @Test
    @DisplayName("其它指标一律放行 —— 别顺手把自研指标一起干掉")
    void otherMetersUntouched() {
        assertEquals(MeterFilterReply.NEUTRAL,
                filter.accept(new Meter.Id("ainovel.ai.call", Tags.empty(), null, null, Meter.Type.COUNTER)));
        // 没有 uri 标签的 http.server.requests（理论上不应出现）也不能被误过滤而整条丢弃
        assertEquals(MeterFilterReply.NEUTRAL,
                filter.accept(new Meter.Id("http.server.requests", Tags.empty(), null, null, Meter.Type.COUNTER)));
    }
}
