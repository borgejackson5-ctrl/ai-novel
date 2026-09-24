package com.ainovel.module.novel.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 申请工单（管理端列表用）
 */
@Data
public class NovelAppealVO {

    private Long id;
    private Long novelId;
    private String novelTitle;
    private String novelAuthor;

    private Long userId;
    /** 申请人昵称（列表里直接展示，管理端不必再点进去查） */
    private String userNickname;

    private String type;
    private String typeText;
    private String reason;

    private Integer status;
    private String statusText;

    private String adminReply;
    private LocalDateTime handleTime;
    private LocalDateTime createTime;
}
