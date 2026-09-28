package com.ainovel.module.admin.service;

import com.ainovel.module.admin.domain.vo.AiStatsVO;

/**
 * AI 用量统计（管理端）。
 *
 * <p>数据来自两处，口径不同：调用次数与耗时来自本进程的 Micrometer 指标（重启清零），
 * 当日额度来自 Redis（跨天清零）。返回对象中带有口径提示字段，页面直接展示。
 */
public interface AiStatsService {

    /** 汇总当前 AI 用量：调用、失败、降级、缓存命中、流式收尾与当日额度 */
    AiStatsVO stats();
}
