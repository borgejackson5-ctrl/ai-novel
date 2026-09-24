package com.ainovel.module.admin.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 审核拒绝请求（理由必填）
 */
@Data
public class AuditRejectForm {

    @NotBlank(message = "请填写拒绝理由")
    @Size(max = 200, message = "理由过长")
    private String reason;
}
