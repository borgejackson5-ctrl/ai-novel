package com.ainovel.module.novel.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 生成封面请求
 */
@Data
public class CoverGenerateForm {

    /** 封面描述（书名/题材等，后端会追加风格后缀） */
    @NotBlank(message = "请填写封面描述")
    @Size(max = 200, message = "描述过长")
    private String prompt;
}
