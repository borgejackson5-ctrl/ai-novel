package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态（充值订单 / 解锁订单共用）
 */
@Getter
@AllArgsConstructor
public enum OrderStatusEnum {

    /** 待支付 */
    PENDING(0, "待支付"),

    /** 已支付 */
    PAID(1, "已支付"),

    /** 已取消 */
    CANCELED(2, "已取消");

    private final int code;
    private final String desc;

    public static boolean isValid(Integer code) {
        if (code == null) {
            return false;
        }
        for (OrderStatusEnum status : values()) {
            if (status.code == code) {
                return true;
            }
        }
        return false;
    }
}
