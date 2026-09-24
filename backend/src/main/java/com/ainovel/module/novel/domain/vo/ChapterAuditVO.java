package com.ainovel.module.novel.domain.vo;

import lombok.Data;

/**
 * 章节审核列表项（管理员章节级审核用）
 *
 * <p>正文：新增章取 content、变更章取 pendingContent，均截断 ~500 字节选，避免列表拉全量大字段。
 */
@Data
public class ChapterAuditVO {

    private Long id;
    private Long novelId;
    private String novelTitle;
    private Integer chapterNo;
    private String title;

    /** 正文节选（新增章 = content；变更章 = pendingContent） */
    private String excerpt;

    /** 旧版正文节选（变更章用于新旧对比，新增章为 null） */
    private String oldExcerpt;

    private Integer unlockCoin;
    private Integer auditStatus;
    private String auditResult;
    private String author;
}
