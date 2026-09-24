package com.ainovel.module.monitor.domain.vo;

import com.ainovel.module.monitor.metrics.ApiStat;
import lombok.Data;

import java.util.List;

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
}
