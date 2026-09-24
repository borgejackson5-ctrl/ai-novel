package com.ainovel.common.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 包装另一个拦截器，**跳过 ASYNC 分发**的那一次调用。
 *
 * <p><b>该包装的必要性</b>：SSE（{@code SseEmitter}）与其它异步返回值在完成时，
 * 容器会再执行一次 ASYNC 分发，而 **Spring 会在该次分发中重新执行一遍
 * {@code preHandle}**（拦截器为普通的 {@link HandlerInterceptor}，而非
 * {@code AsyncHandlerInterceptor}，无法获取「这是异步返回」的信号）。
 *
 * <p>对登录校验而言，后果是：{@code StpUtil.checkLogin()} 再次执行，但 Sa-Token 的上下文由
 * servlet Filter 写入 ThreadLocal，而 **Filter 默认仅在 REQUEST 分发时执行**，
 * 此次必然抛 {@code SaTokenContextException}。该症状较为隐蔽：异常发生在响应已提交之后，
 * 客户端实际已收到全部内容，但流未能正常收尾，即**生成已成功，前端却在结尾收到网络错误**。
 *
 * <p>对限流而言，后果是**一次请求被计数两次**：用户实际仅使用一次，额度却减少两次，
 * 表现为「限额小于配置值的一半」。该问题不报错、不打日志，仅能通过核对计数发现。
 *
 * <p>因此两类拦截器均需跳过该次调用。采用包装器而非各自实现判断，是为了使判据只有一处：
 * 判断错误的代价是静默的，不能依赖人工记忆。
 */
public class SkipAsyncDispatchInterceptor implements HandlerInterceptor {

    private final HandlerInterceptor delegate;

    public SkipAsyncDispatchInterceptor(HandlerInterceptor delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws Exception {
        // 判据为 dispatcherType：ServletRequest 上**没有** isAsyncDispatch() 方法，
        // 异步返回的那次分发，类型为 DispatcherType.ASYNC
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        return delegate.preHandle(request, response, handler);
    }
}
