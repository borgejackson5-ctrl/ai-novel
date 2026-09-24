package com.ainovel.common.domain;

import lombok.Data;

/**
 * 分页请求参数
 */
@Data
public class PageParam {

    /** 单页最大条数：限制上限，避免 size 传入超大值导致 DB/ES 分页查询性能下降 */
    public static final long MAX_PAGE_SIZE = 100L;

    /** 页码钳位：小于 1 一律按第 1 页处理 */
    public static long clampPage(long pageNum) {
        return Math.max(1L, pageNum);
    }

    /**
     * 页大小钳位：1 ≤ size ≤ {@link #MAX_PAGE_SIZE}。
     *
     * <p>**对外接口必须显式调用该方法**，不能依赖分页插件的全局兜底：
     * 兜底值（{@code MybatisPlusConfig.MAX_LIMIT}）必须 ≥ 内部批量查询的页大小，
     * 因此比对外接口应有的上限更宽；仅依赖兜底等同于允许一次拉取 200 条，
     * 且后续若调大兜底值，对外接口的限制会一并放宽。
     */
    public static long clampSize(long pageSize) {
        return Math.min(Math.max(1L, pageSize), MAX_PAGE_SIZE);
    }

    private Long pageNum = 1L;
    private Long pageSize = 10L;

    public Long getPageNum() {
        return clampPage(pageNum == null ? 1L : pageNum);
    }

    public Long getPageSize() {
        return clampSize(pageSize == null ? 10L : pageSize);
    }
}
