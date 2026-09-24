package com.ainovel.common.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private Long total;
    private Long pages;
    private Long current;
    private Long size;
    private List<T> list;

    public static <T> PageResult<T> of(long total, long current, long size, List<T> list) {
        long pages = size == 0 ? 0 : (total + size - 1) / size;
        return new PageResult<>(total, pages, current, size, list);
    }
}
