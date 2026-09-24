package com.ainovel.module.ai.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 全文审查任务。
 *
 * <p>进度口径：{@code doneChapters + failedChapters} 中已包含失败的章：
 * {@code doneChapters} 为「已处理完的章数」（含审失败的），{@code failedChapters} 仅为其分类计数。
 * 因此「进度条是否走完」只需一个数即可判断，无需引入第三个数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_review_task")
public class AiReviewTask extends BaseEntity {

    /** 排队中 */
    public static final int STATUS_QUEUED = 0;
    /** 审查中 */
    public static final int STATUS_RUNNING = 1;
    /** 已完成（可能有部分章审失败，见 failedChapters） */
    public static final int STATUS_DONE = 2;
    /** 已中止（额度用尽 / 作者取消）：已审部分保留，可继续审查 */
    public static final int STATUS_ABORTED = 3;
    /** 失败（一章都没审成） */
    public static final int STATUS_FAILED = 4;

    private Long userId;

    private Long novelId;

    /** 书名快照：作品改名/删除后，任务列表仍可显示当时审查的作品 */
    private String novelTitle;

    /**
     * 发起范围的下界章号（含）；{@code null} = 不限（整本）。
     *
     * <p>补派发与续跑均需按该值过滤，否则「只审最近 N 章」的任务会被补派发为整本。
     * 曾出现：最近 1 章的任务执行完成后账面变为 total=1 / done=2。
     */
    private Integer scopeFromChapterNo;

    /** 发起范围的上界章号（含）；{@code null} = 不限（整本）。意义同上 */
    private Integer scopeToChapterNo;

    private Integer status;

    private Integer totalChapters;

    private Integer doneChapters;

    private Integer failedChapters;

    private Integer issueCount;

    /** 本次累计扣除的免费字数（自带 Key 的用户为 0） */
    private Integer chargedUnits;

    /** 失败章节已退回的字数：与 chargedUnits 一并向作者说明 */
    private Integer refundedUnits;

    private Integer reviewedChars;

    /** 给作者看的说明：中止/失败的原因（不出现接口名、机制名这类实现细节） */
    private String message;

    private java.time.LocalDateTime finishTime;
}
