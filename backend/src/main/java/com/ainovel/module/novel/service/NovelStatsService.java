package com.ainovel.module.novel.service;

import com.ainovel.module.novel.domain.vo.NovelStatsVO;

/**
 * 作者数据看板
 *
 * <p>只读聚合，不修改任何业务状态。数据源均为已有表：
 * {@code t_read_history}（逐章阅读记录）+ {@code t_bookshelf}（收藏）+ {@code t_novel}（累计阅读量）。
 * 未为看板新增埋点，逐章阅读记录本就在写入。
 *
 * <p>指标口径：
 * <ul>
 *   <li><b>阅读人数</b>使用去重 UV，而非次数：同一用户反复阅读同一章不重复计数</li>
 *   <li><b>章节曲线下降最陡的一章</b>通常对应读者流失位置，其参考价值高于总阅读量</li>
 *   <li><b>追读率</b> = 最新有记录的章人数 / 首章人数。样本不足时不给出结论，避免误导</li>
 * </ul>
 */
public interface NovelStatsService {

    public NovelStatsVO stats(Long novelId);
}
