package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 虚拟币流水类型
 */
@Getter
@AllArgsConstructor
public enum CoinTypeEnum {

    /** 充值入账 */
    CHARGE("CHARGE", "充值"),

    /** 解锁消费 */
    UNLOCK("UNLOCK", "解锁"),

    /** 反馈奖励（采纳 Bug/合理建议发放） */
    FEEDBACK_REWARD("FEEDBACK_REWARD", "反馈奖励");

    private final String code;
    private final String desc;
}
