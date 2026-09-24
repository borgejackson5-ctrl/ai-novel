package com.ainovel.module.novel.domain.form;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户发布作品请求
 */
@Data
public class NovelPublishForm {

    @NotBlank(message = "请填写书名")
    @Size(max = 100, message = "书名过长")
    private String title;

    @Size(max = 1000, message = "简介过长")
    private String intro;

    /** 分类：用户必选，管理员审核时可修正 */
    @NotNull(message = "请选择分类")
    private Long categoryId;

    @Size(max = 200, message = "标签过长")
    private String tags;

    @Size(max = 255)
    private String coverUrl;

    /** 笔名，留空则回退到用户昵称/用户名 */
    @Size(max = 50, message = "笔名过长")
    private String author;

    /** 章节列表，至少提交一章 */
    @Valid
    @NotEmpty(message = "请至少提交一章")
    private List<ChapterForm> chapters;
}
