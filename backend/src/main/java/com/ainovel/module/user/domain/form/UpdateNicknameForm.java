package com.ainovel.module.user.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改昵称/笔名表单
 */
@Data
public class UpdateNicknameForm {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 30, message = "昵称最长 30 个字符")
    private String nickname;
}
