package com.ainovel.module.ai.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 全文审查的入口数据：进入页面时一次取全，前端无需再发第二个请求。
 *
 * <p>将「最近一次任务」与「本书需审查多少」放在一起是刻意设计：
 * 发起前的确认框需同时显示「将审查 N 章 / 约 M 字」与「今天还剩 K 字」：
 * 三个数来自三处（章节表、任务表、额度计数器），分三次请求容易出现
 * 「按弹窗中的数字点击确认，实际扣费与显示不一致」。
 */
@Data
public class AiReviewOverviewVO {

    /** 最近一次任务；未审查过则为 null */
    private AiReviewTaskVO task;

    /** 本书当前的章节数 */
    private Integer chapterCount;

    /** 全书正文字数合计（按各章字数相加，用于发起前的预估） */
    private Long totalWords;

    /** 今日剩余免费字数；自带 Key 的用户为 null（不限量） */
    private Long remainingUnits;

    /** 是否使用自有 Key（为 true 时额度不足也可审完整本） */
    private Boolean useOwnKey;

    /**
     * 按范围的预估消耗：整本 / 最近 20 章 / 最近 50 章，各带章数、字数与「今日额度是否足够」。
     *
     * <p>给出的是「本次发起需要多少」而非「整本需要多少」：长篇整本往往无法一次审完，
     * 作者需要的是「当前额度能审完哪一段」。
     */
    private List<ScopeEstimate> scopeOptions;

    @Data
    public static class ScopeEstimate {

        /** ALL=整本 / RECENT=最近 N 章（与 AiReviewStartForm 的取值一致） */
        private String scope;

        /** scope=RECENT 时的章数；ALL 时为 null */
        private Integer recentCount;

        /** 该范围包含的章数 */
        private Integer chapters;

        /** 该范围的正文字数合计 ≈ 本次预计消耗的字数 */
        private Long words;

        /** 今日剩余额度是否足够审完该范围；额度未知（自带 Key）时为 true */
        private Boolean enoughQuota;
    }
}
