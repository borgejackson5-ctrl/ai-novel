package com.ainovel.config;

import com.ainovel.module.monitor.interceptor.WebMetricsInterceptor;
import io.micrometer.core.instrument.config.MeterFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 运行指标相关配置：自研慢接口统计的拦截器注册 + 标准指标（Micrometer）的过滤规则。
 */
@Configuration
@RequiredArgsConstructor
public class WebMetricsConfig implements WebMvcConfigurer {

    private final WebMetricsInterceptor webMetricsInterceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(webMetricsInterceptor)
                .addPathPatterns("/**")
                // 指标接口与接口文档自身的请求不计入统计：否则反复刷新监控页会成为
                // 统计中最慢、最频繁的接口，使真正需要关注的业务接口排在后面
                .excludePathPatterns(
                        "/admin/system/metrics",
                        "/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-ui/**",
                        "/error");
    }

    /**
     * 不将抓取端的请求计入 HTTP 指标，与上述排除监控页的理由相同。
     *
     * <p>Spring Boot 会为所有 servlet 请求自动生成 {@code http_server_requests_*}，
     * 包括 actuator 自身。Prometheus 默认每 15 秒抓取一次 {@code /actuator/prometheus}，
     * 一天累计五千余次，足以使其成为请求量最大的接口：查看 p99 时会首先看到它，
     * 真正需要关注的业务接口被淹没。
     */
    @Bean
    MeterFilter denyActuatorHttpMetrics() {
        return MeterFilter.deny(id -> "http.server.requests".equals(id.getName())
                && isActuatorUri(id.getTag("uri")));
    }

    /**
     * 判据抽为纯函数（便于单测）。仅匹配 {@code /actuator} 本身与以 {@code /actuator/} 开头，
     * 不使用 {@code contains}，也不使用 {@code startsWith("/actuator")}：
     * 后两种写法会一并排除 {@code /actuators-*} 这类同前缀的业务路径，
     * 且该误排除是静默的，指标不再上报且无任何提示。
     */
    static boolean isActuatorUri(String uri) {
        return uri != null && (uri.equals("/actuator") || uri.startsWith("/actuator/"));
    }
}
