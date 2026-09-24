package com.ainovel.module.ai.service;

import com.ainovel.module.ai.domain.PolishMode;
import com.ainovel.module.ai.domain.WritingLength;
import com.ainovel.module.ai.domain.entity.AiConfig;

import java.util.function.Consumer;

/**
 * AI 助力创作：续写与润色（学习清单阶段 6）。
 *
 * <p>与「起名/简介」（{@link AiService}）的区别不在于技术复杂度，而在于**输入为作者自己的正文**：
 * <ul>
 *   <li>正文可能达几千字，不能像起名那样将输入放入 URL，因此走 POST；</li>
 *   <li>生成内容会直接进入作者的稿件，因此**一律不自动写入数据库**，由作者确认后保存；</li>
 *   <li>扣费口径必须使作者可理解：送入多少字即扣多少（见各估算方法上的说明）。</li>
 * </ul>
 *
 * <p>**调用顺序有约束**：先 {@code estimateXxxUnits}（同时校验入参）→
 * 再 {@code requireXxxReady}（判断是否可写）→ 取得配置并扣费 → 最后创建 SSE。
 * 其中任何一步顺序颠倒，失败都会表现为「已返回 200、但不输出任何内容」的连接，
 * 前端只能持续等待，页面无可显示的错误。
 */
public interface AiWritingService {

    /**
     * 本次续写会消耗多少字。
     *
     * <p>口径：**只算真正送入模型的那部分正文**（本章末尾若干字）加上作者填写的续写方向，
     * 不算系统提示词。因此整章一万字与仅写两千字，扣费相同：送入的都只是末尾那一小段。
     *
     * <p>与 {@link #continueWriting} 共用同一个「取上文」的方法，不分别计算，
     * 否则修改截取长度后，扣费与实际送入内容会静默不一致。
     */
    int estimateContinueUnits(String content, String direction);

    /**
     * 本次润色会消耗多少字，即**选中那一段**的字数。
     *
     * <p>不采用「按整章计算」：作者选中的是这一段，使其为未选中的部分付费不合理。
     */
    int estimatePolishUnits(String content);

    /**
     * 续写当前是否可写；不可写时抛出业务异常。
     *
     * <p>**必须在创建 SSE 之前调用。** 该方法同时被 {@link #continueWriting} 自身调用一次，
     * 规则只定义在一处，不会出现「控制器放行、服务层又拒绝」这类不一致情况。
     */
    void requireContinueReady(AiConfig config);

    /**
     * 润色当前是否可写；不可写时抛出业务异常。
     *
     * <p>较续写更严格：未配置 Key 时**连演示文字也不返回**。润色结果会被作者直接采纳，
     * 以一段伪造的改写充当结果，作者一旦点击「替换选中」即会替换掉自己的正文。
     */
    void requirePolishReady(AiConfig config);

    /**
     * 续写：顺着本章末尾往下写一段。
     *
     * @param content   编辑器里的整章正文（服务端只取末尾一段作为上文）
     * @param direction 接下来想写什么，可空
     * @param length    长度档位
     * @param config    已解析并扣过费的生效配置
     * @param onChunk   每收到一段增量文本时的回调
     */
    void continueWriting(String content, String direction, WritingLength length,
                         AiConfig config, Consumer<String> onChunk);

    /**
     * 润色：按选定改法重写选中的一段。
     *
     * @param content 作者选中的那一段文字
     * @param mode    改法（保持原意改表达 / 精简 / 加画面感）
     * @param config  已解析并扣过费的生效配置
     * @param onChunk 每收到一段增量文本时的回调
     */
    void polish(String content, PolishMode mode, AiConfig config, Consumer<String> onChunk);
}
