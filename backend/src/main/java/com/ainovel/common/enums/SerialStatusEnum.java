package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 作品连载状态（创作侧状态，与上架/审核相互独立）
 *
 * <p>已完结的作品，章节与内容型字段会被锁定，作者只能修改书名与封面。
 * 恢复连载需通过「申请解除完结」，且转为已完结后存在冷静期。
 */
@Getter
@AllArgsConstructor
public enum SerialStatusEnum {

    /** 连载中（默认）：章节可增删改 */
    SERIALIZING(0, "连载中"),

    /** 已完结：章节与简介等内容型字段锁定，仅书名/封面可改 */
    FINISHED(1, "已完结");

    private final int code;
    private final String desc;

    public static boolean isFinished(Integer code) {
        return code != null && code == FINISHED.code;
    }

    /**
     * 状态码 → 展示文案。由服务端下发，前端不再自行维护枚举映射
     * （否则后续新增「暂停更新」这类状态时，前端会遗漏修改）。
     */
    public static String textOf(Integer code) {
        if (code == null) {
            return SERIALIZING.desc;
        }
        for (SerialStatusEnum status : values()) {
            if (status.code == code) {
                return status.desc;
            }
        }
        return SERIALIZING.desc;
    }
}
