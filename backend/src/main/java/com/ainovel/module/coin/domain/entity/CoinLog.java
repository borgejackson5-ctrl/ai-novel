package com.ainovel.module.coin.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 虚拟币流水实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_coin_log")
public class CoinLog extends BaseEntity {

    private Long userId;
    private Integer changeAmount;
    private String type;
    private Long bizId;
    private String remark;
}
