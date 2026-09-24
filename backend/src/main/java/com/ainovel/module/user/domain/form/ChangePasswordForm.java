package com.ainovel.module.user.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码表单（登录后）：原密码 + 新密码
 */
@Data
public class ChangePasswordForm {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度需为 6-32 位")
    private String newPassword;
}
