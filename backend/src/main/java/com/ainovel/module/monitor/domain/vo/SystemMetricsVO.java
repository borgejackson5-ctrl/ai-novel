package com.ainovel.module.monitor.domain.vo;

import com.ainovel.module.monitor.metrics.ApiStat;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 系统运行指标（管理端「系统健康」页）
 *
 * <p>口径说明：这些数据来自**当前进程的内存**，重启清零、多实例不聚合。
 * 用途是排查当前是否存在变慢的接口，不代表长期趋势；页面上需明确说明，
 * 避免被当作历史监控数据解读。
 */
@Data
public class SystemMetricsVO {

    /** 当前进程启动时间（{@code RuntimeMXBean#getStartTime}，格式 yyyy-MM-dd HH:mm:ss）：统计口径的起点 */
    private String runningSince;

    /** 慢请求阈值（毫秒） */
    private Long slowApiThresholdMs;

    /** 慢 SQL 阈值（毫秒） */
    private Long slowSqlThresholdMs;

    /** 接口维度统计（按慢次数降序） */
    private List<ApiStat> apis;

    /** 最近的慢请求 */
    private List<SlowRecordVO> recentSlowApis;

    /** 最近的慢 SQL */
    private List<SlowRecordVO> recentSlowSqls;

    /** 累计处理的请求数 */
    private Long totalRequests;

    /**
     * 死信队列积压总条数；-1 表示全部队列都读不到（未知，不是「正常」）。
     *
     * <p>死信队列只进不出且没有消费者，此前只有 Prometheus 指标能体现其状态。
     * 而生产部署未接 Prometheus，管理端又无入口 —— 消息持续堆积到 broker 转 blocked 时
     * 全站发不出消息，在此之前浏览器里看不到任何信号。
     */
    private Long dlxBacklog;

    /** 各死信队列的积压明细（队列名 → 条数，-1 表示该队列读不到），用于定位是哪类消息在堆积 */
    private Map<String, Long> dlxQueues;

    /**
     * 待补投的 outbox 消息条数；-1 表示读取失败。
     *
     * <p>MQ 不可达时接口仍返回成功（消息已落本地表），用户无感知、日志仅一行 warn，
     * 该数字上升是「消息发不出去」唯一能提前暴露的地方。
     */
    private Long outboxBacklog;
}
