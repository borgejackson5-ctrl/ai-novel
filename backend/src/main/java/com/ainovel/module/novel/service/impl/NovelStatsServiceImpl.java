package com.ainovel.module.novel.service.impl;

import com.ainovel.common.enums.SerialStatusEnum;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.NovelStatsVO;
import com.ainovel.module.novel.spi.ChapterReaderRow;
import com.ainovel.module.novel.spi.ChapterReaderStats;
import com.ainovel.module.novel.spi.NovelCollectCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.ainovel.module.novel.service.NovelStatsService;
import com.ainovel.module.novel.service.NovelService;

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
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelStatsServiceImpl implements NovelStatsService {

    /** 首章阅读人数低于该值时，追读率不具统计意义，直接不展示 */
    private static final long MIN_SAMPLE_FOR_RATE = 5;

    /** 流失榜取前几名 */
    private static final int DROP_OFF_TOP_N = 3;

    private final NovelService novelService;

    private final ChapterReaderStats chapterReaderStats;

    private final NovelCollectCounter novelCollectCounter;

    public NovelStatsVO stats(Long novelId) {
        // 复用统一的归属门：非作者且非管理员直接 403
        Novel novel = novelService.requireOwnerNovel(novelId);

        NovelStatsVO vo = new NovelStatsVO();
        vo.setNovelId(novel.getId());
        vo.setNovelTitle(novel.getTitle());
        vo.setSerialStatus(novel.getSerialStatus());
        vo.setSerialStatusText(SerialStatusEnum.textOf(novel.getSerialStatus()));
        vo.setTotalChapters(novel.getTotalChapters());
        vo.setReadCount(novel.getReadCount() == null ? 0L : novel.getReadCount());
        vo.setCollectCount(novelCollectCounter.countByNovel(novelId));
        vo.setReaderCount(chapterReaderStats.distinctReaders(novelId));

        List<NovelStatsVO.ChapterStat> chapters = buildChapterStats(novelId);
        vo.setChapters(chapters);
        fillSummary(vo, chapters);
        vo.setDropOffs(buildDropOffs(chapters));
        return vo;
    }

    /** 逐章人数曲线 + 逐章流失 */
    private List<NovelStatsVO.ChapterStat> buildChapterStats(Long novelId) {
        List<ChapterReaderRow> rows = chapterReaderStats.chapterReaders(novelId);
        List<NovelStatsVO.ChapterStat> list = new ArrayList<>(rows.size());
        Long prev = null;
        for (ChapterReaderRow row : rows) {
            NovelStatsVO.ChapterStat s = new NovelStatsVO.ChapterStat();
            s.setChapterNo(row.chapterNo());
            s.setChapterTitle(row.chapterTitle());
            long readers = row.readers() == null ? 0L : row.readers();
            s.setReaders(readers);
            if (prev == null) {
                s.setLost(0L);
            } else {
                s.setLost(Math.max(prev - readers, 0L));
                s.setKeepRate(prev > 0 ? percent(readers, prev) : null);
            }
            prev = readers;
            list.add(s);
        }
        return list;
    }

    /** 首章/最新章人数与追读率 */
    private void fillSummary(NovelStatsVO vo, List<NovelStatsVO.ChapterStat> chapters) {
        if (chapters.isEmpty()) {
            vo.setFirstChapterReaders(0L);
            vo.setLatestChapterReaders(0L);
            return;
        }
        long first = chapters.get(0).getReaders() == null ? 0 : chapters.get(0).getReaders();
        long latest = chapters.get(chapters.size() - 1).getReaders() == null
                ? 0 : chapters.get(chapters.size() - 1).getReaders();
        vo.setFirstChapterReaders(first);
        vo.setLatestChapterReaders(latest);
        // 样本不足时给出比例会造成误导，故不展示
        vo.setRetentionRate(first >= MIN_SAMPLE_FOR_RATE ? percent(latest, first) : null);
    }

    /** 流失榜：仅在确实出现下滑的章节中取前几名，避免平缓曲线占据榜单 */
    private List<NovelStatsVO.ChapterStat> buildDropOffs(List<NovelStatsVO.ChapterStat> chapters) {
        return chapters.stream()
                .filter(c -> c.getLost() != null && c.getLost() > 0)
                .sorted(Comparator.comparingLong(NovelStatsVO.ChapterStat::getLost).reversed())
                .limit(DROP_OFF_TOP_N)
                .toList();
    }

    private static String percent(long part, long whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part * 100.0 / whole)
                .setScale(1, RoundingMode.HALF_UP) + "%";
    }

}
