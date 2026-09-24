package com.ainovel.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 榜单类型
 *
 * <p>四种榜共用同一套「Redis ZSet + 本地缓存 + 空标记 + 互斥重建」的读取链路，
 * 仅排序口径不同。榜单名称采用业务表述，前端直接展示，无需自行维护映射。
 */
@Getter
@AllArgsConstructor
public enum RankTypeEnum {

    /** 热门榜：按阅读量 */
    HOT("hot", "热门榜"),

    /** 新书榜：按上架先后（雪花 ID 单调递增 ≈ 入库时间） */
    NEW("new", "新书榜"),

    /** 完本榜：只收已完结作品，按阅读量 */
    FINISHED("finished", "完本榜"),

    /** 收藏榜：按加入书架数 */
    COLLECT("collect", "收藏榜");

    private final String code;
    private final String desc;

    public static RankTypeEnum of(String code) {
        if (code != null) {
            for (RankTypeEnum t : values()) {
                if (t.code.equalsIgnoreCase(code)) {
                    return t;
                }
            }
        }
        return HOT;
    }
}
