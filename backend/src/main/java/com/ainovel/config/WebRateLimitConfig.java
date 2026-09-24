package com.ainovel.config;

import com.ainovel.common.ratelimit.FixedWindowRateLimiter;
import com.ainovel.common.ratelimit.RateLimitInterceptor;
import com.ainovel.common.web.SkipAsyncDispatchInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 接口级限流的拦截器注册。
 *
 * <p>顺序在登录校验之后（{@code order(10)}，Sa-Token 为 {@code order(0)}）：
 * 限流按「登录用户 id，未登录则客户端 IP」计数，需先识别请求归属。
 * 若顺序相反，所有登录用户会共用同一 IP 维度的额度，且该问题静默，
 * 仅表现为实际限额远低于配置值。
 *
 * <p>总开关置于此处而非拦截器内部：关闭时不注册拦截器（不进入拦截器栈），
 * 而非在 {@code preHandle} 中判断布尔值。理由有两点：
 * <ol>
 *   <li>本项目所有依赖均为 mock，若开关放在拦截器的 {@code @Value} 字段上，
 *       单测中该字段恒为默认值，「关闭限流」路径无法被测试覆盖，而线上确实会关闭；</li>
 *   <li>不注册则没有 {@code preHandle} 调用，减少一层开销，排查时也不会看到该拦截器。</li>
 * </ol>
 * 配置类本身是 Spring bean，{@code @Value} 字段可正常注入。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebRateLimitConfig implements WebMvcConfigurer {

    /** 限流拦截器的执行序号，必须大于登录校验（0） */
    private static final int RATE_LIMIT_ORDER = 10;

    /** 故障时可一键关闭限流。默认启用，默认值取「有保护」一侧 */
    @Value("${app.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    private final FixedWindowRateLimiter rateLimiter;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        if (!rateLimitEnabled) {
            log.warn("接口级限流已关闭（app.rate-limit.enabled=false）—— 所有 @RateLimit 注解不生效");
            return;
        }
        // 包装一层以跳过 ASYNC 分发：SSE 完成时容器会再次执行 preHandle，
        // 若不跳过，一次请求会被计数两次，用户仅调用一次但额度消耗两次。
        registry.addInterceptor(new SkipAsyncDispatchInterceptor(new RateLimitInterceptor(rateLimiter)))
                .addPathPatterns("/**")
                .order(RATE_LIMIT_ORDER);
    }
}
