package com.ainovel.module.coin.domain.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 充值请求
 */
@Data
public class ChargeForm {

    @NotNull(message = "充值金额不能为空")
    @Min(value = 1, message = "充值金额必须大于 0")
    private Integer amount;
}
