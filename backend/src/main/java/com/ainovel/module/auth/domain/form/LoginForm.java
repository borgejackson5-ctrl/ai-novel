package com.ainovel.module.auth.domain.form;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录表单
 *
 * <p>identifier 支持用户名或邮箱（后端按是否含 @ 自动识别）。
 */
@Data
public class LoginForm {

    @NotBlank(message = "账号不能为空")
    private String identifier;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 可选：邮箱登录且未注册时，用于「转注册」的邮箱验证码 */
    private String code;

    /** 是否「记住我」（勾选后 15 天长会话，否则默认 1 天短会话） */
    private Boolean rememberMe;
}
