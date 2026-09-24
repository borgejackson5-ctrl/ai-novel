package com.ainovel.module.novel.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作者修改自己作品信息的表单。
 *
 * <p>只覆盖作品的元信息。章节正文有独立的作者编辑入口（走章节影子正文），
 * 整本价由付费章价格自动推导，均不在此表单内。
 */
@Data
public class NovelEditForm {

    @NotBlank(message = "请填写书名")
    @Size(max = 100, message = "书名过长")
    private String title;

    @NotNull(message = "请选择分类")
    private Long categoryId;

    @Size(max = 1000, message = "简介过长")
    private String intro;

    @Size(max = 200, message = "标签过长")
    private String tags;

    @Size(max = 255)
    private String coverUrl;

    /** 笔名，留空则沿用当前笔名 */
    @Size(max = 50, message = "笔名过长")
    private String author;
}
