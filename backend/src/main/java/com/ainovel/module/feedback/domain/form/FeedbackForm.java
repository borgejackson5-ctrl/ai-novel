package com.ainovel.module.feedback.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交反馈表单
 */
@Data
public class FeedbackForm {

    @NotBlank(message = "反馈类型不能为空")
    private String type;

    @NotBlank(message = "反馈内容不能为空")
    @Size(max = 1000, message = "反馈内容最多 1000 字")
    private String content;

    /** 选填：联系方式（便于回访） */
    @Size(max = 100, message = "联系方式最多 100 字")
    private String contact;

    /** 是否匿名提交（0 实名 / 1 匿名，默认实名） */
    private Integer anonymous;
}
