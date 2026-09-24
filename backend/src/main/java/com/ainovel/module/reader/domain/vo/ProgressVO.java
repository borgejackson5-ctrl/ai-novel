package com.ainovel.module.reader.domain.vo;

import lombok.Data;

/**
 * 「继续阅读」书签视图（novelId/chapterId 经 Jackson 全局序列化为字符串）
 */
@Data
public class ProgressVO {

    private Long novelId;

    private Long chapterId;

    private String novelTitle;

    private Integer chapterNo;

    private String chapterTitle;

    private String mode;

    private Integer scrollTop;

    private Integer pageNo;

    /** 客户端最后写入时间戳（ms），前端 pull 时回写，作为下次 push 的 LWW 基准 */
    private Long clientTime;
}
