package com.ainovel.module.monitor.service;

import com.ainovel.module.monitor.domain.vo.SystemMetricsVO;

/**
 * 系统健康指标（管理端只读）
 */
public interface SystemMetricsService {

    public SystemMetricsVO metrics();

    /** 重置统计基线：排查完成后清空，后续统计仅反映新的问题 */
    public void reset();
}
