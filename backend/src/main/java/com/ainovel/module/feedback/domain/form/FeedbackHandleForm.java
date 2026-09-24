package com.ainovel.module.feedback.domain.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员处理反馈表单
 */
@Data
public class FeedbackHandleForm {

    /** 处理结果：1 已采纳 / 2 未采纳 */
    @NotNull(message = "处理结果不能为空")
    private Integer status;

    /** 管理员回复（选填，采纳/未采纳均可回） */
    @Size(max = 500, message = "回复最多 500 字")
    private String reply;

    /** 奖励虚拟币数（仅采纳时有效，1~10000） */
    private Integer rewardCoin;
}
