package com.ainovel.module.monitor.metrics;

import com.ainovel.module.monitor.domain.vo.SlowRecordVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 进程内运行指标采集器：接口耗时 + 慢 SQL。
 *
 * <p>采用进程内存储而非写库或推送监控系统：本项目为单实例部署，目标是「打开管理端即可看到
 * 哪个接口在变慢」，为此引入 Prometheus 的成本过高。代价是**重启即清零、多实例不聚合**，
 * 这一点在返回结果中显式说明，避免被解读为历史趋势。
 *
 * <p>内存占用被两处约束住：
 * <ul>
 *   <li>接口维度用 {@link ConcurrentHashMap}，key 是路径模板，条目数受接口数量限制（几十个）；</li>
 *   <li>慢请求/慢 SQL 用**有界环形缓冲**（只留最近 N 条），不随请求量增长。</li>
 * </ul>
 */
@Slf4j
@Component
public class MetricsCollector {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /** 慢请求阈值：超过该值才计入 slowCount 并进入慢榜 */
    @Value("${app.metrics.slow-api-ms:500}")
    private long slowApiThresholdMs;

    /** 慢 SQL 阈值 */
    @Value("${app.metrics.slow-sql-ms:200}")
    private long slowSqlThresholdMs;

    /** 慢榜保留条数（有界，避免无限堆积） */
    @Value("${app.metrics.slow-keep:50}")
    private int slowKeep;

    private final Map<String, ApiStat> apiStats = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<SlowRecordVO> slowApis = new ConcurrentLinkedDeque<>();

    private final ConcurrentLinkedDeque<SlowRecordVO> slowSqls = new ConcurrentLinkedDeque<>();

    public long getSlowApiThresholdMs() {
        return slowApiThresholdMs;
    }

    public long getSlowSqlThresholdMs() {
        return slowSqlThresholdMs;
    }

    /**
     * 记录一次接口调用。
     *
     * @param api    「方法 + 路径模板」，例如 {@code GET /novel/{id}}
     * @param costMs 耗时
     */
    public void recordApi(String api, long costMs) {
        apiStats.computeIfAbsent(api, ApiStat::new).record(costMs, slowApiThresholdMs);
        if (costMs >= slowApiThresholdMs) {
            push(slowApis, new SlowRecordVO(api, costMs, null, now()));
        }
    }

    /** 记录一条慢 SQL */
    public void recordSql(String mapperMethod, String sql, long costMs) {
        if (costMs < slowSqlThresholdMs) {
            return;
        }
        push(slowSqls, new SlowRecordVO(mapperMethod, costMs, sql, now()));
    }

    /** 按慢次数降序返回接口统计（慢次数相同的按平均耗时降序） */
    public List<ApiStat> apiStats() {
        List<ApiStat> list = new ArrayList<>(apiStats.values());
        list.sort(Comparator.comparingLong(ApiStat::getSlowCount).reversed()
                .thenComparing(Comparator.comparingLong(ApiStat::getAvgMs).reversed()));
        return list;
    }

    /** 最近的慢请求（新的在前） */
    public List<SlowRecordVO> recentSlowApis() {
        return new ArrayList<>(slowApis);
    }

    /** 最近的慢 SQL（新的在前） */
    public List<SlowRecordVO> recentSlowSqls() {
        return new ArrayList<>(slowSqls);
    }

    /** 清空统计（排查完某个问题后手动重置基线） */
    public void reset() {
        apiStats.clear();
        slowApis.clear();
        slowSqls.clear();
    }

    /**
     * 有界入队：超过上限则淘汰最旧记录。
     *
     * <p>采用「双端队列 + 超限后从队首淘汰」而非优先队列：此处需要的是「最近的慢请求」，
     * 而非「历史最慢的 N 条」；后者会使数小时前的尖峰长期占据榜单，掩盖当前问题。
     */
    private void push(ConcurrentLinkedDeque<SlowRecordVO> deque, SlowRecordVO vo) {
        deque.addFirst(vo);
        while (deque.size() > slowKeep) {
            deque.pollLast();
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }
}
