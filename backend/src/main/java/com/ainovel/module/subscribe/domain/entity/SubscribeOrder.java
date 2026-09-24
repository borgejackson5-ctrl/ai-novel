package com.ainovel.module.subscribe.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 解锁订单实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_subscribe_order")
public class SubscribeOrder extends BaseEntity {

    private String orderNo;
    private Long userId;
    private Long novelId;
    private Long chapterId;
    private Integer coinAmount;
    private Integer status;
}
