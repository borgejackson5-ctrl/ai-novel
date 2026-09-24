package com.ainovel.module.novel.domain.vo;

import lombok.Data;

/**
 * 作者编辑作品信息的视图（仅作品所有者可见）。
 *
 * <p>同时返回「当前生效值」与「待审影子值」：有变更在审时，页面需要把两者并排展示，
 * 让作者知道前台此刻显示的还是旧值。无变更时 pending* 全为 null。
 */
@Data
public class NovelEditVO {

    private Long id;

    /** ========== 当前生效值（前台正在展示的） ========== */
    private String title;
    private Long categoryId;
    private String categoryName;
    private String coverUrl;
    private String intro;
    private String tags;
    private String author;

    /** 审核状态：0 待审 / 1 通过 / 2 拒绝 / 3 变更待审 */
    private Integer auditStatus;
    private String auditResult;
    private Integer status;

    /** ========== 待审影子值（有变更在审时才有值） ========== */
    private String pendingTitle;
    private String pendingIntro;
    private String pendingCoverUrl;
    private String pendingTags;
    private Long pendingCategoryId;
    private String pendingAuthor;
}
