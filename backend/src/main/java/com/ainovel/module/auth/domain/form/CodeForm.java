package com.ainovel.module.auth.domain.form;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送验证码表单
 */
@Data
public class CodeForm {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 场景：register（注册）/ login（邮箱未注册时转注册）/ reset（忘记密码重置） */
    @NotBlank(message = "场景不能为空")
    private String scene;
}
