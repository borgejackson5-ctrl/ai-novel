package com.ainovel.module.coin.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值订单实体
 *
 * <p>支付状态机：0 待支付 → 1 已支付 / 2 已取消
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_recharge_order")
public class RechargeOrder extends BaseEntity {

    /** 充值订单号（唯一） */
    private String orderNo;

    private Long userId;

    /** 充值虚拟币数量 */
    private Integer coinAmount;

    /** 支付金额（元），简化 1 币 = 1 元 */
    private BigDecimal payAmount;

    /** 0 待支付 / 1 已支付 / 2 已取消 */
    private Integer status;

    /** 支付截止时间（下单时间 + 15 分钟，超时由延迟队列自动关单） */
    private LocalDateTime expireTime;

    private LocalDateTime payTime;
}
