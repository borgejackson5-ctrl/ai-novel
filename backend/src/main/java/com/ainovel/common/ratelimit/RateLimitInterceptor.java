package com.ainovel.common.ratelimit;

import com.ainovel.common.exception.RateLimitException;
import com.ainovel.common.util.IpUtil;
import com.ainovel.common.util.LoginUserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 执行 {@link RateLimit} 的拦截器。
 *
 * <p><b>采用拦截器而非 AOP</b>：限流需按请求维度（用户 id / 客户端 IP）计数，二者均来自
 * 请求上下文；拦截器与项目中已有的登录校验、HTTP 指标埋点处于同一层，排查时只需查看一处。
 * 此外它在 {@code preHandle} 中判定，天然早于 controller，因此「校验与扣费均在**建流之前**」
 * 这一约定对流式接口同样成立（SSE 不会先开流再发现超限）。
 *
 * <p><b>执行顺序位于登录校验之后</b>（{@code order} 更大）：先识别「身份」，才能按用户 id 计数。
 * 否则所有登录用户将共用同一个 IP 维度的额度。数据来源见 {@link #currentDimension()}。
 *
 * <p>本类为 {@code @Component} 但**不被 Spring 注入任何 {@code @Value}**：总开关在
 * {@link com.ainovel.config.WebRateLimitConfig} 中判断（关闭时不注册）。本项目全部依赖均为
 * mock，将开关放在拦截器字段上会使单测中其恒为默认值，导致「关闭限流」这条路径
 * 在单测中永远无法覆盖，而线上确实会关闭。
 */
@Slf4j
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final FixedWindowRateLimiter limiter;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        // 静态资源、错误页等 handler 不是 HandlerMethod，无法读取注解
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit annotation = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (annotation == null) {
            return true;
        }
        if (!limiter.tryAcquire(annotation.name(), currentDimension(),
                annotation.limit(), annotation.window())) {
            log.warn("限流命中：{} {} 次数超过 {}/{}s",
                    request.getMethod(), request.getRequestURI(),
                    annotation.limit(), annotation.window());
            // 抛出「还需等待的秒数」，由全局异常处理器转为 429 + Retry-After
            throw new RateLimitException(annotation.window());
        }
        return true;
    }

    /**
     * 计数维度：登录用户按 id，游客按客户端 IP。
     *
     * <p>前缀 {@code u:} / {@code ip:} 不可省略。省略后 userId 与形似 IP 的字符串之间
     * 理论上可能 key 冲突（更重要的是日志中无法区分该计数属于谁），而添加前缀无额外成本。
     *
     * <p>与榜单限流（{@code RankServiceImpl}）口径一致：
     * 「游客也能看」不等于「游客这条路径不限流」。
     */
    private String currentDimension() {
        Long userId = LoginUserUtil.getUserIdOrNull();
        return userId != null ? "u:" + userId : "ip:" + IpUtil.getClientIp();
    }
}
