package com.ainovel.module.ai.domain.vo;

import lombok.Data;

/**
 * 全文审查的一条问题（已做**跨章归并**）。
 *
 * <p>同一处问题出现在多章属常见情况（某个人名始终写错、某个标点始终用错）。
 * 逐条铺开会使作者在几百条中反复看到同一问题，真正需要单独处理的反被淹没。
 * 因此：相同 {@code dedupKey} 仅保留一条，另以 {@code chapterNos} 列出还出现在哪几章。
 *
 * <p>注意归并仅发生在**展示层**，数据库中仍为一章一行：删除某章的问题记录
 * 不会影响其他章，也无需为「取消归并」迁移数据。
 */
@Data
public class AiReviewIssueVO {

    private Long id;

    /** 代表条目的章节（列表用它跳转） */
    private Long chapterId;

    private Integer chapterNo;

    private String chapterTitle;

    /** 长章切段后 >1；为 1 时前端不显示「第 N 段」 */
    private Integer segmentNo;

    /** 错别字 / 语病 / 标点 / 前后不一致 */
    private String type;

    private String excerpt;

    private String suggestion;

    /** 同一问题出现在几章（含本章） */
    private Integer chapterCount;

    /** 出现过的章号，如 "3、7、12"；超过 8 章时截断并加「等」 */
    private String chapterNos;
}
