package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户反馈类型
 */
@Getter
@AllArgsConstructor
public enum FeedbackTypeEnum {

    /** 使用感受 */
    FEELING("FEELING", "使用感受"),

    /** 功能建议 */
    SUGGESTION("SUGGESTION", "功能建议"),

    /** Bug 反馈 */
    BUG("BUG", "Bug 反馈");

    private final String code;
    private final String desc;

    /** 取类型的中文描述（未知 code 回退成 code 本身，避免通知里出现空白） */
    public static String descOf(String code) {
        for (FeedbackTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type.desc;
            }
        }
        return code == null ? "反馈" : code;
    }

    /** 校验 code 是否为合法类型 */
    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        for (FeedbackTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
