package com.ainovel.module.monitor.interceptor;

import com.ainovel.module.monitor.metrics.MetricsCollector;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 接口耗时埋点
 *
 * <p>统计维度使用**路径模板**而非真实路径：{@code /novel/{id}} 只有一个统计条目，
 * 否则 {@code /novel/1}、{@code /novel/2}…… 会为每个作品生成一条独立记录，
 * 内存占用与可读性均会恶化。
 *
 * <p>埋点自身需保持极低开销：此处仅做一次 map 累加，不写数据库、不打日志、不阻塞。
 * 观测手段若比被观测对象更慢，将成为新的问题。
 */
@Component
@RequiredArgsConstructor
public class WebMetricsInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = "metrics.startAt";

    private final MetricsCollector metricsCollector;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        request.setAttribute(START_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        Object start = request.getAttribute(START_ATTR);
        if (!(start instanceof Long startAt)) {
            return;
        }
        try {
            metricsCollector.recordApi(apiName(request), System.currentTimeMillis() - startAt);
        } catch (Exception e) {
            // 埋点失败不得影响请求本身
        }
    }

    private static String apiName(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = pattern != null ? pattern.toString() : request.getRequestURI();
        return request.getMethod() + " " + path;
    }
}
