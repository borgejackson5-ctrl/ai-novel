package com.ainovel.module.novel.domain.vo;

import lombok.Data;

/**
 * 章节列表项（目录用，不含正文，避免列表接口拉取大字段）
 */
@Data
public class ChapterVO {

    private Long id;
    private Integer chapterNo;
    private String title;
    private Integer wordCount;

    /** 本章解锁所需虚拟币，0 为免费章 */
    private Integer unlockCoin;

    /** 审核状态（作者视角目录展示；读者目录已过滤不可见章，此字段通常为 1/3） */
    private Integer auditStatus;

    /** 审核意见/拒绝原因 */
    private String auditResult;
}
