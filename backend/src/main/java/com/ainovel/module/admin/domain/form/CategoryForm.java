package com.ainovel.module.admin.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类新增 / 修改表单
 */
@Data
public class CategoryForm {

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 50, message = "分类名称最多 50 个字")
    private String name;

    /** 排序值，越小越靠前（决定前台分类导航的先后） */
    @NotNull(message = "排序值不能为空")
    @Min(value = 0, message = "排序值不能小于 0")
    @Max(value = 9999, message = "排序值不能大于 9999")
    private Integer sort;

    /** 1 启用 / 0 禁用 */
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能是 0 或 1")
    @Max(value = 1, message = "状态只能是 0 或 1")
    private Integer status;
}
