package com.ainovel.common.constant;

/**
 * AI 相关文案常量：**同一句话被多处使用时应集中到此**。
 *
 * <p>该约定源于一类反复出现的问题：同一条规则在两个类中各写一份，其中一份未同步更新。
 * 已出现过两次：流式扣费头名（{@link SseConstant}）与前端 SSE 帧解析，
 * 均表现为「一处正确、一处错误，且不报错」。
 *
 * <p>{@link #AI_NOT_OPEN_MSG} 为第三处：起名/简介/续写/润色各自的实现中均重复了
 * 该文案，而**审查的两条链路完全缺失**（平台未配置 Key 时仍调用上游并失败）。
 * 集中到一处后，新链路判断「AI 是否可用」只需调用
 * {@code AiConfigService#requireModelAvailable}，不会遗漏。
 */
public final class AiConstant {

    /**
     * 「平台未配置 Key（或不允许降级）」时返回给用户的文案。
     *
     * <p>措辞除「稍后再试」外刻意不含其它引导：**不应写「去配置 Key」**，
     * 普通作者无法配置平台 Key，该类提示只会引导用户寻找不存在的入口。
     */
    public static final String AI_NOT_OPEN_MSG = "AI 功能暂未开放，请稍后再试";

    private AiConstant() {
    }
}
