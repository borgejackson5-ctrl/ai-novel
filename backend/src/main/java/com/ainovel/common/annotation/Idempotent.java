package com.ainovel.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明该接口接受调用方的<b>幂等键</b>（请求头 {@code Idempotency-Key}）。
 *
 * <p><b>它与「唯一索引 / 状态机」不同，二者均需具备。</b>唯一索引保护服务端自身
 * （并发重复解锁不会扣两次币），而幂等键解决的是**调用方不可见的那次重试**：
 * 请求实际已成功、但响应在网络中丢失，客户端重发。前者只会告知「已解锁」，
 * 后者能将**第一次的完整响应**（含订单号）原样返回，使客户端看到的结果与未重试时一致。
 *
 * <p>未携带该请求头时按原样执行，因此它是**可选**的：旧客户端不受影响，新客户端按需携带。
 *
 * <p>用法与语义：
 * <ul>
 *   <li>同一用户 + 同一方法 + 同一键 ⇒ 仅第一次实际执行，之后直接重放第一次的响应；</li>
 *   <li>第一次仍在执行时再次进入 ⇒ 409（{@code REPEAT_SUBMIT}），而非排队等待：等待时长不可预估，
 *       由客户端决定是否重试比服务端挂起线程更可控；</li>
 *   <li>第一次**失败** ⇒ 键立即失效，同一键可重试。理由见切面中的说明。</li>
 * </ul>
 *
 * <p>仅应标注在**返回 {@code ResponseDTO} 的写接口**上：读接口不存在幂等问题，
 * 而缓存读结果会使「重放」变为「读到旧数据」。守门测试 {@code IdempotentGuardTest} 校验该约束。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {

    /**
     * 第一次成功后，该响应可被重放的时长（秒），默认 24 小时。
     *
     * <p>24 小时为业界（Stripe）的通用取值：足以覆盖真实的重试窗口，
     * 且不会使键无限堆积。
     */
    long ttlSeconds() default 24 * 60 * 60;

    /**
     * 「正在处理」占位状态的存活时长（秒），默认 60。
     *
     * <p>这实际是一种**自我保护**：若进程处理中途退出（或业务阻塞），
     * 该键会自行过期，后续请求可重新执行。否则客户端将被一个永不结束的
     * 「处理中」状态阻断，且无法自行解除（仅能人工删除 Redis key）。
     * 因此该值应略大于这些接口的正常耗时，但**不应**设置过长。
     */
    long processingSeconds() default 60;
}
