package com.ainovel.module.admin.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * AI 调用统计（管理端「AI 用量」页）。
 *
 * <p>调用次数与耗时取自本进程的 Micrometer 指标：重启清零、多实例不聚合，
 * 反映的是当前的使用分布与失败情况，不代表长期趋势。
 * 当日额度取自 Redis，按日期滚动、跨天自动清零，重启不影响。
 *
 * <p>各分组的取值以「码 + 展示名」成对返回：码进指标与接口字段，展示名仅供渲染。
 * 二者合一会使「改展示文案」牵动指标标签。
 *
 * <p>「场景」由调用方在发请求时声明（见 {@code AiScene}），而非按调用的方法名推断：
 * 同步起名与搜索意图解析同属非流式调用，按方法名区分会把两者记到同一组。
 */
@Data
public class AiStatsVO {

    /** 采集时间（yyyy-MM-dd HH:mm:ss） */
    private String generatedAt;

    /** 应用启动时间，即调用次数与耗时的统计起点（yyyy-MM-dd HH:mm:ss） */
    private String startedAt;

    /** 合计 */
    private Totals totals;

    /** 按场景与模型分组（调用次数降序） */
    private List<SceneStat> scenes;

    /** AI 降级记录（按场景与原因分组） */
    private List<DegradeStat> degrade;

    /** 缓存读取（按缓存名分组） */
    private List<CacheStat> cache;

    /** 流式接口收尾结果（按入口与结果分组） */
    private List<SseStat> sse;

    /** 当日额度用量（来自 Redis） */
    private List<QuotaStat> quota;

    /** 合计：成功、失败与成功率 */
    @Data
    public static class Totals {

        /** 调用成功次数 */
        private long succeeded;

        /** 调用失败次数 */
        private long failed;

        /** 尝试次数（成功与失败之和） */
        private long attempts;

        /** 成功率，取值 0~1；无调用时为 0 */
        private double successRate;

        /** 输入 token 合计（仅统计上游返回用量的调用） */
        private long promptTokens;

        /** 输出 token 合计（仅统计上游返回用量的调用） */
        private long completionTokens;
    }

    /** 单个「场景 + 模型」的调用统计 */
    @Data
    public static class SceneStat {

        /** 场景码，与指标的 scene 标签一致 */
        private String scene;

        /** 场景展示名；场景已从枚举下线时回落为场景码 */
        private String label;

        private String model;

        private long succeeded;

        private long failed;

        /** 平均耗时（毫秒），由 Timer 的累计时长除以次数得出 */
        private long avgCostMs;

        /** 最大耗时（毫秒） */
        private long maxCostMs;

        /**
         * 输入 token 合计。
         *
         * <p>与调用次数口径不同：用量由上游返回，未返回时此处为 0 而次数照常计数，
         * 因此不能由「次数 × 平均值」推算。
         */
        private long promptTokens;

        /** 输出 token 合计，口径同 {@link #promptTokens} */
        private long completionTokens;
    }

    /** 一次降级：AI 不可用时由调用方回退到本地逻辑 */
    @Data
    public static class DegradeStat {

        private String scene;

        private String label;

        /** 降级原因码：no_key / failed / timeout / local_fallback */
        private String reason;

        /** 降级原因的展示名；原因码未知时回落为原因码本身 */
        private String reasonLabel;

        private long count;
    }

    /** 单个缓存的命中情况 */
    @Data
    public static class CacheStat {

        /** 缓存名，取自缓存 key 的前两段（如 novel:detail） */
        private String cache;

        /** 缓存名的展示名；未登记的名称回落为缓存名本身 */
        private String label;

        private long hit;

        private long miss;

        /** 命中率，取值 0~1；无读取时为 0 */
        private double hitRate;
    }

    /** 流式入口的收尾结果 */
    @Data
    public static class SseStat {

        /** 入口名：generate / continue / polish */
        private String api;

        /** 入口展示名；未登记的入口回落为入口名本身 */
        private String apiLabel;

        /** done / cancelled / timeout / error */
        private String outcome;

        /** 收尾结果展示名；未登记的结果回落为结果码本身 */
        private String outcomeLabel;

        private long count;
    }

    /** 当日额度用量 */
    @Data
    public static class QuotaStat {

        /** 展示名 */
        private String name;

        /** 当日已用 */
        private long used;

        /** 每日上限 */
        private long limit;

        /** 剩余额度，不小于 0 */
        private long remaining;
    }
}
