package com.ainovel.common.exception;

/**
 * 流式响应中断：<b>客户端已断开，或流本身已结束</b>。
 *
 * <p><b>两种来源，对上层是同一件事：流对端已不存在。</b>
 * <ol>
 *   <li>客户端主动中断（关闭页面、点击「停止」、网络中断）：推送方 {@code send} 抛
 *       {@link java.io.IOException}；</li>
 *   <li>流已结束：最常见的是 <b>SSE 超时</b>，容器回收连接、Spring 将 emitter
 *       标记为 completed，推送方下一次 {@code send} 抛 {@code IllegalStateException}。</li>
 * </ol>
 *
 * <p>超时归入此类而非「生成失败」的原因：进入第 2 种情况的前提是**生成线程正在推送数据**，
 * 即模型持续输出，只是未能在时限内完成。但**额度仍全额退还**（见 {@link #refundable}）：
 * 用户等待较长时间后未获得任何内容，按「失败退」的口径应当退还；平台为一次超长生成承担损失，
 * 优于让用户为未获得的内容付费。
 * 归入此类的真正目的是**避免其被包装为「调用 AI 接口失败，请检查 Key 与网络」**，
 * 该文案会将排查方向引偏（超时却排查 Key），且日志中会多出两条 ERROR 与整页堆栈。
 *
 * <p><b>它并非一种失败，因此不能按业务异常处理</b>：
 * <ul>
 *   <li>是否退还额度取决于原因（{@link #refundable}）：主动停止不退，超时退；</li>
 *   <li>不应再向响应写入任何内容，对端已不存在；</li>
 *   <li>更不应被包装为其它异常：它需一路抛至最外层，若中途被捕获，
 *       模型侧会继续完成本次生成（消耗输出 token、占用线程）。
 *       若不归入此类，它会被包装为「调用 AI 接口失败，请检查 Key 与网络」。</li>
 * </ul>
 *
 * <p>因此它**不继承 {@link BusinessException}**：一旦继承，全局异常处理器会将其视为
 * 「可返回给用户的业务错误」，而此时并无用户在等待响应。
 *
 * <p>由 SSE 的推送方在「无法写出」时抛出（见 {@code AiWritingController} /
 * {@code AiController}），由 {@link com.ainovel.module.ai.client.AiChatClient} 的实现
 * 原样向上抛，抛出该异常会使上游的 HTTP 请求被取消。
 */
public class StreamCancelledException extends RuntimeException {

    /**
     * 本次中断是否退还已预扣的额度。
     *
     * <p>分为两种取值，原因是**中断原因不同，是否退费也不同**：
     * <ul>
     *   <li>客户端主动停止（默认 {@code false}）：界面已注明「额度不会退回」，
     *       且成本确已产生；</li>
     *   <li>流超时（{@code true}）：用户未获得任何内容，不应收费。
     *       与「失败退」口径一致：本次并非用户主动放弃，而是服务端未完成交付。</li>
     * </ul>
     *
     * <p>注意默认值为 {@code false}：现有两种来源中以「主动停止」居多。后续新增中断来源时，
     * **需显式确定是否退还**，遗漏传参的后果是扣减用户额度，且为静默发生。
     */
    private final boolean refundable;

    /** 客户端主动中断：不退额度（界面上写明了这一条） */
    public StreamCancelledException(String message) {
        this(message, null, false);
    }

    public StreamCancelledException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public StreamCancelledException(String message, Throwable cause, boolean refundable) {
        super(message, cause);
        this.refundable = refundable;
    }

    public boolean isRefundable() {
        return refundable;
    }
}
