package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 小说审核状态
 *
 * <p>与 {@link ChapterAuditStatusEnum}（章节级）区分。作品比章节多两种「非首次」的在审态：
 * {@link #MODIFY_WAIT}（修改已有信息）与 {@link #RESHELVE_WAIT}（申请重新上架）。
 * 二者的共同点是**作品此前已通过审核**，因此读者可见性判断（{@link #isVisible}）需将其计入，
 * 否则作者提交变更或申请后，作品会从读者视野中消失。
 */
@Getter
@AllArgsConstructor
public enum AuditStatusEnum {

    /** 待审核（首次提交，读者不可见） */
    WAIT(0, "待审核"),

    /** 审核通过（读者可见） */
    PASS(1, "审核通过"),

    /** 审核拒绝（读者不可见） */
    REJECT(2, "审核拒绝"),

    /** 变更待审（已通过作品有修改在审，读者可见旧值） */
    MODIFY_WAIT(3, "修改审核中"),

    /** 重新上架待审（已下架作品申请恢复分发，读者仍可打开详情读已解锁章节，但作品尚未恢复分发） */
    RESHELVE_WAIT(4, "重新上架审核中");

    private final int code;
    private final String desc;

    public static boolean isValid(Integer code) {
        if (code == null) {
            return false;
        }
        for (AuditStatusEnum status : values()) {
            if (status.code == code) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读者可见：审核通过、变更待审、重新上架待审。
     *
     * <p>后两者均为「此前已通过审核」的作品，前台按旧值或既有内容展示；是否**对外分发**
     * （进入书库、榜单、搜索）另看 {@code status}，本方法仅判断详情页能否打开。
     */
    public static boolean isVisible(Integer code) {
        return code != null && (code == PASS.code || code == MODIFY_WAIT.code || code == RESHELVE_WAIT.code);
    }

    /** 是否处于待人工处理队列（首次待审 + 变更待审 + 重新上架待审） */
    public static boolean isPending(Integer code) {
        return code != null && (code == WAIT.code || code == MODIFY_WAIT.code || code == RESHELVE_WAIT.code);
    }
}
