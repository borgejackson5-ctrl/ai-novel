package com.ainovel.module.novel.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 作者数据看板
 *
 * <p>指标口径对齐成熟平台的作者后台，仅选取可由现有数据计算的部分：
 * <ul>
 *   <li>阅读人数（去重 UV）：较累计阅读量更难被刷量影响</li>
 *   <li>章节阅读人数曲线 + 逐章流失：直接定位读者流失的章节</li>
 *   <li>追读率：最新章读者 / 首章读者，衡量留存情况</li>
 *   <li>收藏数：反映长期预期，较阅读量更能说明问题</li>
 * </ul>
 *
 * <p>数据源为 t_read_history（已有表），不额外埋点：每章一条阅读记录，
 * 章节维度天然可聚合。
 */
@Data
public class NovelStatsVO {

    private Long novelId;
    private String novelTitle;
    private Integer serialStatus;
    private String serialStatusText;
    private Integer totalChapters;

    /** 累计阅读量（t_novel.read_count，含 24h 去重后的每次阅读） */
    private Long readCount;

    /** 收藏数（加入书架） */
    private Long collectCount;

    /** 去重阅读人数（UV） */
    private Long readerCount;

    /** 首章阅读人数：追读率的分母 */
    private Long firstChapterReaders;

    /** 最新有阅读记录的章节人数：追读率的分子 */
    private Long latestChapterReaders;

    /** 追读率，形如 "42.9%"；样本不足（首章人数过少）时为 null，前端展示「样本不足」 */
    private String retentionRate;

    /** 逐章阅读人数曲线（按章序升序） */
    private List<ChapterStat> chapters;

    /** 流失最多的章节（前 3 条，仅含确实出现下滑的章） */
    private List<ChapterStat> dropOffs;

    @Data
    public static class ChapterStat {
        private Integer chapterNo;
        private String chapterTitle;
        /** 读过该章的读者数（去重） */
        private Long readers;
        /** 相对上一章流失的读者数；首章为 0 */
        private Long lost;
        /** 相对上一章的留存比例，形如 "88.5%"；首章为 null */
        private String keepRate;
    }
}
