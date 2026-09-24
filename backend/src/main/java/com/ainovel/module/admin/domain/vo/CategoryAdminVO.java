package com.ainovel.module.admin.domain.vo;

import lombok.Data;

/**
 * 分类管理项（管理端列表）
 */
@Data
public class CategoryAdminVO {

    private Long id;

    private String name;

    private Integer sort;

    /** 1 启用 / 0 禁用（禁用后前台分类导航不再显示它） */
    private Integer status;

    /**
     * 该分类下未删除的作品数。
     *
     * <p>两处使用该字段：删除前的前置校验，以及提示管理员该操作影响的书籍数量。
     */
    private Long novelCount;
}
