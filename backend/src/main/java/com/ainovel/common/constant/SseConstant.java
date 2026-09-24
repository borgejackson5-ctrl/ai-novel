package com.ainovel.common.constant;

/**
 * 流式接口（SSE）的对外约定。
 *
 * <p><b>单独定义常量类的原因</b>：该约定**前端也在使用**（{@code utils/aiStream.js} 读取该头名），
 * 而此前在两处 controller 中各写一份的实现已出现问题：
 * 起名/简介的流式版（{@link com.ainovel.module.ai.controller.AiController#generateStream}）
 * 未设置该响应头，导致同一「流式 AI 调用」在续写/润色上可获取扣费数、在起名/简介上无法获取。
 * 表现为前端显示 0 且不报错。
 */
public final class SseConstant {

    private SseConstant() {
    }

    /**
     * 本次调用扣减的字数。通过**响应头**下发，不放入事件流。
     *
     * <p>使用响应头的原因：作者需要第一时间获知本次调用的额度消耗，而流一旦开始即无法回退。
     * 放入响应头后，前端**在读到第一段文字之前**即可获取，也无需为此新增一个
     * 「先计算一次再请求一次」的接口。
     *
     * <p>该值可能缺失（例如后续的非扣费型流式接口），因此前端在读取失败时也需正常运行，
     * 不能将其视为必填。
     */
    public static final String HEADER_CHARGED_UNITS = "X-AI-Charged-Units";
}
