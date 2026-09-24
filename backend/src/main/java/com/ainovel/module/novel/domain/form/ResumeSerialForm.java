package com.ainovel.module.novel.domain.form;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 作者申请解除完结（恢复连载）
 */
@Data
public class ResumeSerialForm {

    /** 申请理由，选填；管理员据此判断是否批准 */
    @Size(max = 500, message = "申请理由最多 500 字")
    private String reason;
}
