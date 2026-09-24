package com.ainovel.common.exception;

import com.ainovel.common.code.ErrorCode;
import lombok.Getter;

/**
 * 触发限流。
 *
 * <p>使用独立异常而非直接抛 {@code BusinessException(RATE_LIMIT)} 的原因：需要将
 * 「还需等待的秒数」传递给客户端（转为 {@code Retry-After} 响应头，前端可据此显示倒计时）。
 * 各限流点的窗口长度不同，全局异常处理器只能获取错误码、无法获取该值，因此由抛出方携带。
 *
 * <p>对外文案中包含秒数是有意为之：用户看到「请 60 秒后再试」才能判断应当等待还是刷新，
 * 仅提示「操作过于频繁」会导致其反复点击，使限额被更快消耗。
 */
@Getter
public class RateLimitException extends BusinessException {

    /** 建议客户端等待的秒数，等于该限流点的窗口长度 */
    private final int retryAfterSeconds;

    public RateLimitException(int retryAfterSeconds) {
        super(ErrorCode.RATE_LIMIT, "操作过于频繁，请 " + retryAfterSeconds + " 秒后再试");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
