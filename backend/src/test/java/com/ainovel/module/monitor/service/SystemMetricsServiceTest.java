package com.ainovel.module.monitor.service;

import com.ainovel.common.metrics.DlxBacklogMetrics;
import com.ainovel.common.metrics.OutboxBacklogMetrics;
import com.ainovel.module.monitor.domain.vo.SystemMetricsVO;
import com.ainovel.module.monitor.metrics.MetricsCollector;
import com.ainovel.module.monitor.service.impl.SystemMetricsServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * 管理端「运行状态」页的组装。
 *
 * <p>重点约束消息侧的两项积压必须真的出现在返回值里：生产部署未接 Prometheus，
 * 管理端是该信息唯一的出口，字段漏填不会有任何报错，只会让运维看不到积压。
 */
@ExtendWith(MockitoExtension.class)
class SystemMetricsServiceTest {

    @Mock
    private MetricsCollector metricsCollector;

    @Mock
    private DlxBacklogMetrics dlxBacklogMetrics;

    @Mock
    private OutboxBacklogMetrics outboxBacklogMetrics;

    private SystemMetricsService systemMetricsService;

    @BeforeEach
    void setUp() {
        systemMetricsService = new SystemMetricsServiceImpl(
                metricsCollector, dlxBacklogMetrics, outboxBacklogMetrics);
    }

    @Test
    @DisplayName("运行状态页带上死信与 outbox 两项积压（生产未接 Prometheus，这是唯一能看到它们的地方）")
    void metrics_carriesMqBacklog() {
        when(metricsCollector.apiStats()).thenReturn(List.of());
        when(metricsCollector.recentSlowApis()).thenReturn(List.of());
        when(metricsCollector.recentSlowSqls()).thenReturn(List.of());
        when(dlxBacklogMetrics.currentBacklog()).thenReturn(4L);
        when(dlxBacklogMetrics.backlogDetails())
                .thenReturn(Map.of("ai.audit.dlx.queue", 4L));
        when(outboxBacklogMetrics.currentBacklog()).thenReturn(-1L);

        SystemMetricsVO vo = systemMetricsService.metrics();

        assertEquals(4L, vo.getDlxBacklog(), "死信积压漏填 → 运维看不到消息在堆积");
        assertEquals(4L, vo.getDlxQueues().get("ai.audit.dlx.queue"));
        // -1 是「读不到」，不能被替换成 0（0 的含义是「一切正常」）
        assertEquals(-1L, vo.getOutboxBacklog(), "outbox 读数要原样透出，-1 表示未知");
    }
}
