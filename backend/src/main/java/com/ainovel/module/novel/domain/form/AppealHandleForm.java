package com.ainovel.module.novel.domain.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理端处理申请工单
 */
@Data
public class AppealHandleForm {

    /** true 通过 / false 驳回 */
    @NotNull(message = "请选择处理结果")
    private Boolean approved;

    /** 处理意见，驳回时建议写明原因，作者会看到 */
    @Size(max = 500, message = "处理意见最多 500 字")
    private String reply;
}
