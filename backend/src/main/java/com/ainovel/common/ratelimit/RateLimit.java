package com.ainovel.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级限流：同一「限流点」在 {@link #window()} 秒内最多放行 {@link #limit()} 次。
 *
 * <p>标注在 controller 的方法上，由 {@link RateLimitInterceptor} 统一执行。采用注解而非一份
 * yaml 配置表，原因是<b>限额应与接口写在一起</b>：修改接口的人可直接看到其限额，
 * 也不会出现「配置中写有某路径、而该路径已改名」这类难以排查的脱节。
 * 代价是限额无法热更新，对本项目可接受，确需调整时修改总开关（{@code app.rate-limit.enabled}）。
 *
 * <p><b>计数维度为「登录用户 id，未登录则客户端 IP」</b>，未做成可选项：
 * 一个接口只会有一种合理的维度，增加枚举即增加一处可配置错误的地方。
 * 因此「游客」路径天然由 IP 覆盖，批量注册小号无法刷量，更换账号无法更换 IP。
 *
 * <p>注意它与「按用户额度」（{@link DailyQuotaLimiter}）不同：后者为<b>每天的总量</b>、
 * 与费用相关；本注解为<b>短时间内的速率</b>、用于防刷防压。两者同时生效。
 *
 * @see RateLimitInterceptor
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RateLimit {

    /**
     * 限流点名字，用于拼 Redis key。
     *
     * <p>多个接口使用**同一个名字**表示共享同一个计数器，例如将「书库列表」与「按作者查」
     * 合并为一个额度，避免通过切换接口刷同一份数据。不同名字之间互不影响。
     */
    String name();

    /** 窗口内允许的次数，必须为正数（守门测试会检查） */
    int limit();

    /** 窗口长度（秒），必须为正数 */
    int window() default 60;
}
