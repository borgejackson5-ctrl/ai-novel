package com.ainovel.module.coin.domain.message;

import lombok.Data;

/**
 * 充值订单超时关单消息（延迟队列 TTL 到期后投递到关单队列）
 */
@Data
public class OrderCloseMessage {

    /** 待超时关闭的充值订单号 */
    private String orderNo;
}
