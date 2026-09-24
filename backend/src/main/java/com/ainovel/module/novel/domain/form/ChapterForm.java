package com.ainovel.module.novel.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户投稿时的单个章节
 */
@Data
public class ChapterForm {

    @Size(max = 100, message = "章节标题过长")
    private String title;

    @NotBlank(message = "章节正文不能为空")
    private String content;

    /** 本章解锁所需虚拟币，null/0 为免费章；限制 0~10 万防负数与溢出 */
    @Min(value = 0, message = "解锁币不能为负")
    @Max(value = 100000, message = "解锁币超出上限")
    private Integer unlockCoin;
}
