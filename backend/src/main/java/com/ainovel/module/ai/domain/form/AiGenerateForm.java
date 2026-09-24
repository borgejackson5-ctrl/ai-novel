package com.ainovel.module.ai.domain.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI 生成请求
 */
@Data
public class AiGenerateForm {

    /** TITLE / INTRO */
    @NotBlank(message = "生成类型不能为空")
    private String type;

    @NotBlank(message = "输入内容不能为空")
    private String input;
}
