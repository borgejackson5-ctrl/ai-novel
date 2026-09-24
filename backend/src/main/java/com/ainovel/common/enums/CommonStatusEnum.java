package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用启用/禁用状态（用户、小说上下架、分类等）
 */
@Getter
@AllArgsConstructor
public enum CommonStatusEnum {

    /** 禁用/下架 */
    DISABLED(0, "禁用"),

    /** 启用/上架 */
    ENABLED(1, "启用");

    private final int code;
    private final String desc;

    public static boolean isValid(Integer code) {
        if (code == null) {
            return false;
        }
        for (CommonStatusEnum status : values()) {
            if (status.code == code) {
                return true;
            }
        }
        return false;
    }
}
