package com.ainovel.module.message.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 站内信实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_message")
public class Message extends BaseEntity {

    /** 接收者用户 ID */
    private Long userId;

    /** 类型：AUDIT_SUBMIT / AUDIT_PASS / AUDIT_REJECT */
    private String type;

    private String title;

    private String content;

    /** 关联业务 ID（如 novelId） */
    private Long relatedId;

    /** 0 未读 / 1 已读 */
    private Integer isRead;
}
