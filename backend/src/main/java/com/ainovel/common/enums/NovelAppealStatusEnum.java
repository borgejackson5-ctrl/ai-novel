package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 作品申请工单的处理状态
 */
@Getter
@AllArgsConstructor
public enum NovelAppealStatusEnum {

    /** 待处理：管理员尚未处理，作者侧入口显示「申请审核中」 */
    PENDING(0, "待处理"),

    /** 已通过：业务动作已执行（如恢复为连载中） */
    APPROVED(1, "已通过"),

    /** 已驳回：业务状态不变 */
    REJECTED(2, "已驳回");

    private final int code;
    private final String desc;
}
