package com.ainovel.module.monitor.metrics;

import lombok.Data;

/**
 * 单个接口的耗时统计（进程内内存统计，重启即清零）
 *
 * <p>只累计「次数 / 总耗时 / 最大耗时 / 慢次数」而不保存单次样本，使内存占用与
 * 接口数量成正比而非与请求量成正比；长期运行的服务不会因埋点而增长内存。
 * 因此这里提供平均值与最差值，不提供 P95：精确分位数需保存样本或引入直方图库，
 * 对「快速发现哪个接口变慢」这一目标而言不值得。
 */
@Data
public class ApiStat {

    /** 接口标识：HTTP 方法 + 路径模板（用模板而非真实路径，否则 /novel/1、/novel/2 会各算一条） */
    private final String api;

    private long count;

    private long totalMs;

    private long maxMs;

    /** 超过慢请求阈值的次数：平均值会被大量快请求拉平，慢次数更能反映「有用户被卡住」 */
    private long slowCount;

    public synchronized void record(long costMs, long slowThresholdMs) {
        count++;
        totalMs += costMs;
        if (costMs > maxMs) {
            maxMs = costMs;
        }
        if (costMs >= slowThresholdMs) {
            slowCount++;
        }
    }

    /** 平均耗时（毫秒） */
    public long getAvgMs() {
        return count == 0 ? 0 : totalMs / count;
    }

    /** 慢请求占比（百分比，保留一位小数） */
    public double getSlowRatio() {
        if (count == 0) {
            return 0;
        }
        return Math.round(slowCount * 1000.0 / count) / 10.0;
    }
}
