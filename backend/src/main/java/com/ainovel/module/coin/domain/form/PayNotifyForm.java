package com.ainovel.module.coin.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 支付网关回调请求（第三方 / 模拟收银台调用）
 *
 * <p>{@code amount} 参与签名，防止攻击者篡改回调金额；{@code nonce} 用于防重放，
 * {@code timestamp} 用于新鲜度校验，{@code sign} 为 HMAC-SHA256 签名。
 */
@Data
public class PayNotifyForm {

    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    @NotNull(message = "支付金额不能为空")
    private Integer amount;

    @NotNull(message = "时间戳不能为空")
    private Long timestamp;

    @NotBlank(message = "nonce 不能为空")
    private String nonce;

    @NotBlank(message = "签名不能为空")
    private String sign;
}
