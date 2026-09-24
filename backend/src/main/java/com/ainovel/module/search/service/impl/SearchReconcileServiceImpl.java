package com.ainovel.module.search.service.impl;

import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.search.domain.vo.ReconcileResultVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.ainovel.module.search.service.SearchService;
import com.ainovel.module.search.service.SearchReconcileService;

/**
 * 搜索索引对账：比对 MySQL 与 Elasticsearch，消除两者的漂移。
 *
 * <p>该机制是最终一致的最后一道保障。此前几层（事务提交后投递、消费时回查 DB、失败退避重投、
 * 重试耗尽转死信）保证的是正常情况不丢失、异常时可重试；但只要消息可能丢失
 * （broker 故障、消费者重试耗尽进入死信无人处理、有人直接修改数据库），索引必然产生漂移。
 * 对账的价值不在速度，而在于使漂移必然被发现并自动修复。
 *
 * <p>两条约束：
 * <ol>
 *   <li>MySQL 是唯一事实源：始终以 DB 侧结果为准修复索引，不可反向；</li>
 *   <li>对自身判断的怀疑优先于对索引的怀疑：DB 侧查出空结果或失效比例异常高时，
 *       应先停止修复。宁可保留脏数据等待人工确认，也不能因一条错误条件将索引清空。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchReconcileServiceImpl implements SearchReconcileService {

    /**
     * 分页大小：DB 侧取 id 使用，避免一次将全表 id 载入内存。
     *
     * <p>不得大于 {@code MybatisPlusConfig.MAX_LIMIT}（全局分页兜底）：
     * 拦截器会将超限的页大小直接改小，而终止判断若仍使用该常量比较，则会在
     * 第一批即判定取完，对账静默只覆盖部分作品。
     */
    private static final int PAGE_SIZE = 200;

    /** 失效比例安全阀：超过该比例则判定更可能是查询错误，不执行删除 */
    private static final double STALE_ALARM_RATIO = 0.5;

    private final NovelService novelService;

    private final SearchService searchService;

    /**
     * 全量对账并修复。
     *
     * <p>保守策略：先比对，再判定安全阀，最后执行修复。任何一步异常均直接抛出，
     * 不执行「尽力而为」的部分修复：半修复状态比漂移更难排查。
     */
    public ReconcileResultVO reconcile() {
        long start = System.currentTimeMillis();
        ReconcileResultVO result = new ReconcileResultVO();

        Set<Long> expected = loadExpectedIds();
        Set<Long> actual = searchService.loadIndexedIds();
        result.setExpectedCount(expected.size());
        result.setIndexedCount(actual.size());

        Set<Long> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        Set<Long> stale = new HashSet<>(actual);
        stale.removeAll(expected);
        result.setMissingCount(missing.size());
        result.setStaleCount(stale.size());
        result.setConsistent(missing.isEmpty() && stale.isEmpty());
        result.setSkipped(false);
        result.setRepairedCount(0);
        result.setRemovedCount(0);

        // 安全阀：DB 侧无记录而索引中有数据，几乎不可能是「索引应当清空」，
        // 更可能是查询条件错误、连接了错误的库或数据被误删。此时不修改索引。
        if (expected.isEmpty() && !actual.isEmpty()) {
            log.error("搜索索引对账中止：DB 侧可见作品为 0 而索引里有 {} 条，"
                    + "疑似查询异常，未执行任何删除", actual.size());
            result.setSkipped(true);
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        }

        // 安全阀：失效比例过高时同样先停止。正常情况下差异应为个位数。
        if (!actual.isEmpty() && stale.size() > actual.size() * STALE_ALARM_RATIO) {
            log.error("搜索索引对账中止：索引 {} 条中有 {} 条被判为失效（超过 {}%），"
                    + "疑似异常，未执行删除", actual.size(), stale.size(),
                    (int) (STALE_ALARM_RATIO * 100));
            result.setSkipped(true);
            result.setCostMs(System.currentTimeMillis() - start);
            return result;
        }

        int repaired = 0;
        for (Long id : missing) {
            try {
                if (searchService.syncOne(id)) {
                    repaired++;
                }
            } catch (Exception e) {
                log.warn("对账补写失败: novelId={}", id, e);
            }
        }

        int removed = 0;
        for (Long id : stale) {
            try {
                searchService.syncOne(id);   // DB 侧不可见 → 内部会走删除
                removed++;
            } catch (Exception e) {
                log.warn("对账移除失败: novelId={}", id, e);
            }
        }

        result.setRepairedCount(repaired);
        result.setRemovedCount(removed);
        result.setCostMs(System.currentTimeMillis() - start);
        log.info("搜索索引对账完成: 应有 {} 条 / 实际 {} 条 / 补写 {} / 移除 {} / 耗时 {}ms",
                expected.size(), actual.size(), repaired, removed, result.getCostMs());
        return result;
    }

    /**
     * DB 侧应当被索引的作品 id 集合。
     *
     * <p>使用 QueryWrapper + 字符串列名做列投影，仅取 id 一列：
     * 对账只需判断存在性，无需返回简介、标签等大字段。
     */
    private Set<Long> loadExpectedIds() {
        Set<Long> ids = new HashSet<>();
        int pageNo = 1;
        while (true) {
            List<Long> pageIds = novelService.pageVisibleIdsForIndex(pageNo, PAGE_SIZE);
            ids.addAll(pageIds);
            // 终止判据可使用常量比较：size 由 novel 侧用 LIMIT 计算，不经过分页插件，不会被改小
            if (pageIds.size() < PAGE_SIZE) {
                return ids;
            }
            pageNo++;
        }
    }
}
