package com.ainovel.module.history.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阅读历史视图（novelId/chapterId 经 Jackson 序列化为字符串）
 */
@Data
public class ReadHistoryVO {

    private Long novelId;

    private Long chapterId;

    private Integer chapterNo;

    private String novelTitle;

    private String chapterTitle;

    private LocalDateTime createTime;
}
