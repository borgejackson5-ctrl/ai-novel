package com.ainovel.module.subscribe.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 解锁订单展示对象（管理后台）
 */
@Data
public class SubscribeOrderVO {

    private Long id;

    private String orderNo;

    private Long userId;

    /** 下单用户名（管理列表展示） */
    private String username;

    private Long novelId;

    private Long chapterId;

    /** 消费虚拟币数量 */
    private Integer coinAmount;

    /** 0 待支付 / 1 已支付 / 2 已取消 */
    private Integer status;

    private LocalDateTime createTime;
}
