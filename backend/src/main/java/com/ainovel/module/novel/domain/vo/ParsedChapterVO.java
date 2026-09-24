package com.ainovel.module.novel.domain.vo;

import lombok.Data;

/**
 * TXT 解析出的单章（用户端投稿预览用，仅解析不写入数据库）
 */
@Data
public class ParsedChapterVO {

    /** 章节标题（无标题行时为「第 N 章」） */
    private String title;

    /** 章节正文 */
    private String content;

    /** 本章字数 */
    private Integer wordCount;

    /** 本章解锁所需虚拟币（首章免费为 0，其余按导入定价） */
    private Integer unlockCoin;
}
