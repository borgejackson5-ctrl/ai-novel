package com.ainovel.module.ai.domain.form;

import lombok.Data;

/**
 * 全文审查的发起参数。**全部可选**：不传任何参数时的行为与原实现一致（整本）。
 *
 * <p>支持选择范围的原因：整本一次发起，长篇（233 章）需两万多字额度，
 * 额度耗尽即停止在 3% 左右，作者首次使用看到的不是结论，而是「仅执行了很少一部分」。
 * 按范围发起可一次执行完成并取得完整结论，剩余部分次日继续。
 */
@Data
public class AiReviewStartForm {

    /**
     * 审查范围：
     * <ul>
     *   <li>{@code ALL}：整本（默认，即不传时的行为）</li>
     *   <li>{@code RECENT}：最近 N 章，N 取 {@link #recentCount}，默认 20</li>
     *   <li>{@code RANGE}：指定的章号闭区间，见 {@link #fromChapterNo} / {@link #toChapterNo}</li>
     * </ul>
     */
    private String scope;

    /** scope=RECENT 时的章数（默认 20，上限由服务端钳制） */
    private Integer recentCount;

    /** scope=RANGE 时的起始章号（含），不传表示不设下界 */
    private Integer fromChapterNo;

    /** scope=RANGE 时的结束章号（含），不传表示不设上界 */
    private Integer toChapterNo;
}
