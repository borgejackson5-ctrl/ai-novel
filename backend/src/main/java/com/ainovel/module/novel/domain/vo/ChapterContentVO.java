package com.ainovel.module.novel.domain.vo;

import lombok.Data;

/**
 * 章节正文视图（通过解锁校验后返回）
 */
@Data
public class ChapterContentVO {

    private Long id;
    private Long novelId;
    private Integer chapterNo;
    private String title;
    private String content;
    private Integer wordCount;
    /** 上一章 ID（首章为 null），阅读器上下章导航用，避免为导航拉全量目录 */
    private Long prevChapterId;
    /** 下一章 ID（末章为 null），同上 */
    private Long nextChapterId;
}
