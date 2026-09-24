package com.ainovel.module.ai.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 章节审查接口的返回。
 *
 * <p>**{@code ok} 与「issues 为空」是两件不同的事**，前端必须分别处理：
 * <ul>
 *   <li>{@code ok=true, issues=[]}：已审，未发现问题；</li>
 *   <li>{@code ok=false}：本次**未审成**（模型不可用 / 返回无法解析 / 超时），
 *       此时由 {@code message} 给出用户可理解的说明，且**本次不消耗免费额度**（已退回）。</li>
 * </ul>
 * 将第二种情况一并视为「无问题」属于最危险的静默失败：作者会认为自己的稿件已被审查。
 */
@Data
public class ChapterReviewVO {

    /** 审查是否确实完成 */
    private Boolean ok;

    /** ok=false 时的说明（面向用户，不出现接口名/机制名这类实现细节） */
    private String message;

    /** 一句话总评 */
    private String summary;

    /** 问题清单 */
    private List<IssueVO> issues;

    /**
     * 本次送审的**正文长度**（字数），使作者对「本次审查了多少内容」有明确感知。
     *
     * <p>与计费口径一致：额度只按正文估算，不含提示词。提示词是平台的固定成本，
     * 不随用户输入变化，转嫁给作者既不公平也解释不清（不应出现正文 75 字却扣 539 字的情况）。
     */
    private Integer reviewedChars;

    /** 该章切分为几段送审（仅长章有意义；1 表示一次调用审完） */
    private Integer segments;

    /**
     * 因「正文中找不到原文片段」而被丢弃的条数。
     *
     * <p>这是**反幻觉**的可观测指标：正常应为 0；持续不为 0 说明模型的 excerpt 需要收紧
     * （提示词中已要求原样引用，此处为兜底）。
     */
    private Integer droppedIssues;

    @Data
    public static class IssueVO {

        /** 错别字 / 语病 / 标点 / 前后不一致 */
        private String type;

        /** 正文中的原样片段（前端据此在正文中定位） */
        private String excerpt;

        private String suggestion;
    }
}
