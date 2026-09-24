package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户反馈处理状态
 */
@Getter
@AllArgsConstructor
public enum FeedbackStatusEnum {

    /** 待处理 */
    PENDING(0, "待处理"),

    /** 已采纳（可能附虚拟币奖励） */
    ADOPTED(1, "已采纳"),

    /** 未采纳（可附回复说明） */
    REJECTED(2, "未采纳");

    private final int code;
    private final String desc;
}
