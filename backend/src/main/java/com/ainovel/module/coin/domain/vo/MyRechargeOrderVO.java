package com.ainovel.module.coin.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值订单展示对象（用户侧「我的钱包」）
 *
 * <p>与 {@link RechargeOrderVO}（管理后台）的区别：不包含 userId / username，
 * 用户仅查询本人订单，无跨用户展示需求。
 */
@Data
public class MyRechargeOrderVO {

    /** 充值订单号 */
    private String orderNo;

    /** 充值虚拟币数量 */
    private Integer coinAmount;

    /** 支付金额（元） */
    private BigDecimal payAmount;

    /** 0 待支付 / 1 已支付 / 2 已取消 */
    private Integer status;

    /** 支付截止时间（待支付时前端据此显示剩余时间） */
    private LocalDateTime expireTime;

    private LocalDateTime payTime;

    private LocalDateTime createTime;
}
