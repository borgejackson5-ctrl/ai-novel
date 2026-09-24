package com.ainovel.module.monitor.service.impl;

import com.ainovel.module.monitor.domain.vo.SystemMetricsVO;
import com.ainovel.module.monitor.metrics.ApiStat;
import com.ainovel.module.monitor.metrics.MetricsCollector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import com.ainovel.module.monitor.service.SystemMetricsService;

/**
 * 系统健康指标（管理端只读）
 */
@Service
@RequiredArgsConstructor
public class SystemMetricsServiceImpl implements SystemMetricsService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MetricsCollector metricsCollector;

    public SystemMetricsVO metrics() {
        List<ApiStat> apis = metricsCollector.apiStats();
        SystemMetricsVO vo = new SystemMetricsVO();
        vo.setRunningSince(FMT.format(Instant.ofEpochMilli(
                ManagementFactory.getRuntimeMXBean().getStartTime())
                .atZone(ZoneId.systemDefault())));
        vo.setSlowApiThresholdMs(metricsCollector.getSlowApiThresholdMs());
        vo.setSlowSqlThresholdMs(metricsCollector.getSlowSqlThresholdMs());
        vo.setApis(apis);
        vo.setRecentSlowApis(metricsCollector.recentSlowApis());
        vo.setRecentSlowSqls(metricsCollector.recentSlowSqls());
        vo.setTotalRequests(apis.stream().mapToLong(ApiStat::getCount).sum());
        return vo;
    }

    /** 重置统计基线：排查完成后清空，后续统计仅反映新的问题 */
    public void reset() {
        metricsCollector.reset();
    }
}
