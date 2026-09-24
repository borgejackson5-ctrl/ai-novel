package com.ainovel.module.ai.client;

import com.ainovel.module.ai.domain.ChapterReviewReport;

/**
 * 「将正文交由模型审查、取得结构化问题清单」这一职责。
 *
 * <p>抽出该接口有两条理由：
 * <ol>
 *   <li>{@code ChapterReviewServiceImpl} 中承载的是**业务规则**（归属校验、额度扣减与退回、
 *       反幻觉过滤、「未审成」与「无问题」的区分），这些规则不应依赖任何具体的 AI 框架写法；</li>
 *   <li>因此测试这些规则时可提供一个「固定返回」的替身，断言「发出的请求内容」与
 *       「返回结果的处理方式」，而不必 mock 一串 prompt/tools/toolContext/call/entity 的链式调用
 *       （该 mock 方式只要链上缺少一个方法便会静默返回 mock，使错误路径也被判定为通过）。</li>
 * </ol>
 *
 * <p>当前实现为 {@link SpringAiChapterReviewer}（Spring AI 1.1.8 的工具调用 + 结构化输出）。
 * 不将其并入 {@code AiChatClient} 的原因：该接口是「单次问答」的契约，两个实现（手写/框架）均遵循它；
 * 工具调用与结构化输出属于**新功能**，按学习清单的结论直接使用框架实现，不再要求手写版同样实现一遍。
 */
public interface ChapterReviewer {

    /**
     * 审查一次。
     *
     * @return 模型给出的结构（可能为空结构，表示它没按要求返回）
     * @throws RuntimeException 调用失败或输出无法解析，由调用方决定如何向用户说明
     */
    ChapterReviewReport review(ChapterReviewRequest request);
}
