package com.ainovel.module.ai.domain;

import java.util.List;

/**
 * 一章审查的结果（内部口径）。
 *
 * <p>不直接使用 HTTP 层的 {@code ChapterReviewVO}：全文审查是**异步任务**，
 * 结果需写入数据库、按任务汇总，并区分「该章未审成」与「该章审过且无问题」；
 * VO 是面向页面的展示结构，将存储结构挂在它上面会使存储结构随界面需求变化。
 * 两者字段大部分相同，但演进原因不同。
 *
 * <p>**{@code ok} 与 {@code issues.isEmpty()} 必须分别判断**：
 * {@code ok=false} 表示「未审成」（额度已退回），{@code ok=true, issues=[]} 表示「已审且无问题」。
 * 两者混同属于最危险的静默失败，作者会认为稿件已被审查。
 *
 * @param ok            该章是否确实审成
 * @param message       ok=false 时的说明（面向作者，不出现实现细节）
 * @param summary       一句话总评
 * @param issues        问题清单（各段合并去重后）
 * @param reviewedChars 该章送审的**正文**字数（计费口径同为该值）
 * @param segments      切分为几段送审
 * @param droppedIssues 因在正文中核对不上而丢弃的条数（反幻觉指标，正常为 0）
 * @param chargedUnits  本次**净扣除**的免费字数（失败路径恒为 0，额度已退回）
 * @param refundedUnits 本次退回的字数（成功路径恒为 0）
 */
public record ChapterReviewResult(boolean ok, String message, String summary,
                                  List<Issue> issues, int reviewedChars,
                                  int segments, int droppedIssues,
                                  int chargedUnits, int refundedUnits) {

    /**
     * 一条问题。
     *
     * @param segmentNo 落在本章第几段（长章切段后 >1，便于作者定位）
     */
    public record Issue(int segmentNo, String type, String excerpt, String suggestion) {
    }

    /** 未审成，且**未产生费用**（额度已按原数退回） */
    public static ChapterReviewResult failed(String message) {
        return new ChapterReviewResult(false, message, null, List.of(), 0, 0, 0, 0, 0);
    }

    /**
     * 未审成，但本次**扣过费、随后已退回**。
     *
     * <p>净消耗仍为 0。单列 {@code refundedUnits} 是为了在任务页面说明
     * 「有 3 章未审成，那 9000 字已经退还」。若只报净额，作者看到
     * 「本次消耗 0 字」会怀疑是否实际扣费。
     */
    public static ChapterReviewResult failedAfterRefund(String message, int refundedUnits) {
        return new ChapterReviewResult(false, message, null, List.of(), 0, 0, 0, 0, refundedUnits);
    }
}
