package com.ainovel.module.history.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.history.dao.ReadHistoryMapper;
import com.ainovel.module.history.domain.entity.ReadHistory;
import com.ainovel.module.history.domain.form.ReadHistoryForm;
import com.ainovel.module.history.domain.vo.ReadHistoryVO;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.NovelVisibility;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.spi.ChapterReaderRow;
import com.ainovel.module.novel.spi.ChapterReaderStats;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.ainovel.module.history.service.ReadHistoryService;

/**
 * 阅读历史服务：追加记录 + 最近阅读（按小说去重，每本取最近一次）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadHistoryServiceImpl implements ReadHistoryService, ChapterReaderStats {

    /** 去重前先扫多少条历史（按小说去重要有足够窗口；不得大于 MybatisPlusConfig.MAX_LIMIT） */
    private static final int SCAN_LIMIT = 200;

    private final ReadHistoryMapper readHistoryMapper;

    private final NovelMapper novelMapper;

    /**
     * 记录一次阅读（幂等：同一用户同一章只保留一条，重复阅读只刷新时间）。
     *
     * <p>阅读记录是「打开就上报」的高频写，用户刷新一次页面就会重复触发；
     * 按章节去重之后，表的大小跟「读过多少章」成正比，而不是跟「刷新了多少次」成正比。
     */
    public void record(ReadHistoryForm form) {
        Long userId = LoginUserUtil.getUserId();
        ReadHistory h = new ReadHistory();
        // 走自定义 upsert 语句，MyBatis-Plus 不会代填主键，这里显式生成雪花 ID
        h.setId(IdWorker.getId());
        h.setUserId(userId);
        h.setNovelId(form.getNovelId());
        h.setChapterId(form.getChapterId());
        h.setChapterNo(form.getChapterNo());
        h.setNovelTitle(form.getNovelTitle());
        h.setChapterTitle(form.getChapterTitle());
        readHistoryMapper.upsertIgnoreDuplicate(h);
    }

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
                                          LocalDateTime startTime, LocalDateTime endTime) {
        Long userId = LoginUserUtil.getUserId();
        int safePageNum = Math.max(1, pageNum);
        int safePageSize = (int) Math.min(Math.max(1, pageSize), PageParam.MAX_PAGE_SIZE);

        Page<ReadHistory> window = readHistoryMapper.selectPage(
                new Page<>(1, SCAN_LIMIT),
                new LambdaQueryWrapper<ReadHistory>()
                        .eq(ReadHistory::getUserId, userId)
                        .ge(startTime != null, ReadHistory::getCreateTime, startTime)
                        .lt(endTime != null, ReadHistory::getCreateTime, endTime)
                        .orderByDesc(ReadHistory::getId));

        Map<Long, ReadHistory> dedup = new LinkedHashMap<>();
        for (ReadHistory r : window.getRecords()) {
            dedup.putIfAbsent(r.getNovelId(), r);
        }
        List<ReadHistoryVO> all = filterReadable(dedup.values().stream()
                .map(h -> BeanUtil.copyProperties(h, ReadHistoryVO.class))
                .toList());

        int from = Math.min((safePageNum - 1) * safePageSize, all.size());
        int to = Math.min(from + safePageSize, all.size());
        return PageResult.of(all.size(), safePageNum, safePageSize, all.subList(from, to));
    }

    /**
     * 过滤出当前对外可见的作品。一次 IN 查询批量判定，避免逐条回查。
     */
    private List<ReadHistoryVO> filterReadable(List<ReadHistoryVO> list) {
        if (list.isEmpty()) {
            return list;
        }
        List<Long> novelIds = list.stream()
                .map(ReadHistoryVO::getNovelId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (novelIds.isEmpty()) {
            return List.of();
        }
        // 字符串列投影 + QueryWrapper：只取 id 一列构成存在性集合，不做对象映射。
        // 可见性条件须统一走 NovelVisibility，不得在本处另写 eq/in 条件，否则属于口径漂移。
        QueryWrapper<Novel> readableWrapper = new QueryWrapper<Novel>()
                .select("id")
                .in("id", novelIds);
        NovelVisibility.appendTo(readableWrapper);
        Set<Long> readable = novelMapper.selectList(readableWrapper)
                .stream().map(Novel::getId).collect(Collectors.toSet());
        return list.stream().filter(v -> readable.contains(v.getNovelId())).toList();
    }

    /** {@inheritDoc} 去重 UV：同一用户多次阅读同一章仅计为一名读者 */
    public long distinctReaders(Long novelId) {
        Long n = readHistoryMapper.selectDistinctReaders(novelId);
        return n == null ? 0L : n;
    }

    public List<ChapterReaderRow> chapterReaders(Long novelId) {
        return readHistoryMapper.selectChapterReaders(novelId).stream()
                .map(r -> new ChapterReaderRow(toInt(r.get("chapterNo")),
                        r.get("chapterTitle") == null ? null : r.get("chapterTitle").toString(),
                        toLong(r.get("readers"))))
                .toList();
    }

    private static long toLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static Integer toInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

}
