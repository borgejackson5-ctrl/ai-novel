package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 章节审核状态
 *
 * <p>与 {@link AuditStatusEnum}（作品级）区分：章节有「变更待审」这一第四态，
 * 支撑「已发布章节修改走影子正文，审核通过原子替换、拒绝无损回退」。
 *
 * <p>读者可见性 = {@link #PASS} 或 {@link #MODIFY_WAIT}（后者见旧版 content）。
 */
@Getter
@AllArgsConstructor
public enum ChapterAuditStatusEnum {

    /** 待审（新增章节，读者不可见） */
    WAIT(0, "待审核"),

    /** 审核通过（读者可见） */
    PASS(1, "审核通过"),

    /** 审核拒绝（读者不可见） */
    REJECT(2, "审核拒绝"),

    /** 变更待审（已发布章节有修改在审，读者可见旧版） */
    MODIFY_WAIT(3, "修改审核中");

    private final int code;
    private final String desc;

    public static boolean isValid(Integer code) {
        if (code == null) {
            return false;
        }
        for (ChapterAuditStatusEnum status : values()) {
            if (status.code == code) {
                return true;
            }
        }
        return false;
    }

    /** 读者可见：审核通过 或 变更待审（均有已发布正文） */
    public static boolean isVisible(Integer code) {
        return code != null && (code == PASS.code || code == MODIFY_WAIT.code);
    }
}
