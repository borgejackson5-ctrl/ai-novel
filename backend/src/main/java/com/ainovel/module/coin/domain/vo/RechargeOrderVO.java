package com.ainovel.module.coin.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值订单展示对象（管理后台）
 */
@Data
public class RechargeOrderVO {

    private Long id;

    private String orderNo;

    private Long userId;

    /** 下单用户名（管理列表展示） */
    private String username;

    /** 充值虚拟币数量 */
    private Integer coinAmount;

    /** 支付金额（元） */
    private BigDecimal payAmount;

    /** 0 待支付 / 1 已支付 / 2 已取消 */
    private Integer status;

    private LocalDateTime payTime;

    private LocalDateTime createTime;
}
