package com.ainovel.module.admin.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理后台数据看板聚合返回
 */
@Data
@Builder
public class DashboardVO {

    /** 用户总数 */
    private long userTotal;

    /** 今日新增用户 */
    private long userToday;

    /** 小说总数 */
    private long novelTotal;

    /** 上架中小说数 */
    private long novelOnline;

    /** 待人工终审的作品数 */
    private long pendingAudit;

    /** 累计充值金额（元，仅已支付） */
    private BigDecimal rechargeAmount;

    /** 累计充值单数（仅已支付） */
    private long rechargeCount;

    /** 累计解锁单数（仅已支付） */
    private long subscribeCount;

    /** 累计消耗虚拟币（仅已支付） */
    private long subscribeCoin;

    /** 内容热度 Top5（按阅读量） */
    private List<TopNovel> hotNovels;

    /** 近期订单（充值 + 解锁合并，最多 8 条） */
    private List<RecentOrder> recentOrders;

    /**
     * 热度榜小说
     */
    @Data
    @Builder
    public static class TopNovel {
        private Long id;
        private String title;
        private String categoryName;
        private Long readCount;
        private Long likeCount;
    }

    /**
     * 近期订单行（充值/解锁合并展示）
     */
    @Data
    @Builder
    public static class RecentOrder {
        /** 订单类型：RECHARGE 充值 / SUBSCRIBE 解锁 */
        private String type;
        private String orderNo;
        private String username;
        /** 业务描述：如「充值 ¥30」/「解锁《逆袭》」 */
        private String desc;
        /** 充值单为金额(元)，解锁单为消耗币数 */
        private BigDecimal amount;
        private Integer status;
        private LocalDateTime createTime;
    }
}
