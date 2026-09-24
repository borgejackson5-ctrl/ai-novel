package com.ainovel.module.history.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.history.domain.form.ReadHistoryForm;
import com.ainovel.module.history.domain.vo.ReadHistoryVO;
import java.time.LocalDateTime;

/**
 * 阅读历史服务：追加记录 + 最近阅读（按小说去重，每本取最近一次）
 */
public interface ReadHistoryService {

    /**
     * 记录一次阅读（幂等：同一用户同一章只保留一条，重复阅读只刷新时间）。
     *
     * <p>阅读记录是「打开就上报」的高频写，用户刷新一次页面就会重复触发；
     * 按章节去重之后，表的大小跟「读过多少章」成正比，而不是跟「刷新了多少次」成正比。
     */
    public void record(ReadHistoryForm form);

    /**
     * 阅读历史分页（按小说去重，每本取最近一次），可按时间区间过滤。
     *
     * <p><b>去重与可见性过滤需在分页之前完成</b>：历史表按「章」存储，同一部作品存在多行，
     * 直接 LIMIT 会使同一页出现同一本书的多次记录，并导致总页数计算错误。因此顺序为
     * 取窗口内原始行 → 按小说去重 → 过滤失效作品 → 最后在内存中切片。
     *
     * <p>窗口上限 {@link #SCAN_LIMIT}（200）即本接口可回溯的范围上限，满足「最近阅读」
     * 语义，同时避免将全表读入内存。时间筛选走 SQL，会进一步收窄窗口。
     */
    public PageResult<ReadHistoryVO> page(int pageNum, int pageSize,
                                          LocalDateTime startTime, LocalDateTime endTime);
}
