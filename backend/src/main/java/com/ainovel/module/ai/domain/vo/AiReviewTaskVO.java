package com.ainovel.module.ai.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全文审查任务的进度视图。
 *
 * <p>**进度与文案均由服务端计算**：{@code percent}、{@code statusText}、{@code canResume}
 * 等判断若分散在页面中，修改一次口径需改动多个页面；且「还需 N 天 / 今日还剩 N 字」这类
 * 门槛提示本属服务端的账面数据，前端不应自行计算。
 *
 * <p>{@code doneChapters} 包含审失败的章（{@code failedChapters} 为其分类计数），
 * 因此进度条到达 100% 即表示任务确已处理完毕，不会出现「进度停留在 58/60 但任务已结束」。
 */
@Data
public class AiReviewTaskVO {

    private Long taskId;

    private Long novelId;

    /** 作品名快照 */
    private String novelTitle;

    /** 0排队中 1审查中 2已完成 3已中止 4失败 */
    private Integer status;

    /** 状态文案（由服务端提供，前端直接展示） */
    private String statusText;

    /** 是否仍在推进：页面据此决定是否继续轮询 */
    private Boolean running;

    /** 结束后是否仍可「继续审查」（已中止/失败，或已完成但存在审失败的章） */
    private Boolean canResume;

    private Integer totalChapters;

    private Integer doneChapters;

    private Integer failedChapters;

    private Integer issueCount;

    /** 0~100，由服务端计算 */
    private Integer percent;

    /** 本次累计扣除的免费字数（自带 Key 时恒为 0，界面不展示该行） */
    private Integer chargedUnits;

    /** 其中已退回的字数（失败的章不收费） */
    private Integer refundedUnits;

    private Integer reviewedChars;

    /** 中止/失败原因，或完成时的一句说明 */
    private String message;

    private LocalDateTime createTime;

    private LocalDateTime finishTime;

    /**
     * 审失败的章节（最多 50 条：仅用于回答「哪几章未审成、原因是什么」，
     * 非完整列表；超过时前端提示「还有 N 章未列出」）。
     */
    private List<FailedChapterVO> failedList;

    /** 未列出到 failedList 里的失败章数 */
    private Integer failedListTruncated;

    @Data
    public static class FailedChapterVO {

        private Long chapterId;

        private Integer chapterNo;

        private String chapterTitle;

        /** 给作者看的原因（正文为空 / 已被删除 / 这次没能完成） */
        private String message;
    }
}
