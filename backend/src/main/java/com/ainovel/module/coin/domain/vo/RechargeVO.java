package com.ainovel.module.coin.domain.vo;

import lombok.Data;

/**
 * 充值订单创建结果
 */
@Data
public class RechargeVO {

    /** 充值订单号（凭它到收银台完成支付） */
    private String orderNo;

    /** 充值虚拟币数量（复用存量单时以实际订单金额为准） */
    private Integer coinAmount;

    /** 剩余支付秒数（前端动态倒计时用） */
    private Integer remainSeconds;

    /** 是否复用存量待支付订单（true=本次返回的是之前未支付的单） */
    private Boolean reused;
}
