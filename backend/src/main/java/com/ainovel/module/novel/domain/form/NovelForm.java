package com.ainovel.module.novel.domain.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 小说新增/编辑表单
 */
@Data
public class NovelForm {

    private Long id;

    @NotBlank(message = "书名不能为空")
    private String title;

    private Long categoryId;
    private String coverUrl;
    private String intro;
    private String tags;
    private String author;
    private Integer totalChapters;
    private Integer coinPrice;
    private Integer status;
}
