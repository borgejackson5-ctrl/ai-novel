package com.ainovel.module.message.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内信返回视图
 */
@Data
public class MessageVO {

    private Long id;

    /** AUDIT_SUBMIT / AUDIT_PASS / AUDIT_REJECT */
    private String type;

    private String title;

    private String content;

    /** 关联业务 ID（如 novelId，前端可据此跳转详情） */
    private Long relatedId;

    /** 0 未读 / 1 已读 */
    private Integer isRead;

    private LocalDateTime createTime;
}
