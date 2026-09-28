package com.ainovel.module.admin.service.impl;

import com.ainovel.common.constant.AiQuotaConstant;
import com.ainovel.common.enums.AiScene;
import com.ainovel.common.ratelimit.DailyQuotaLimiter;
import com.ainovel.module.admin.domain.vo.AiStatsVO;
import com.ainovel.module.admin.service.AiStatsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AI 用量统计实现：从 Micrometer 指标与 Redis 当日计数器读出原始数据并归组。
 *
 * <p>不落库、不做快照：Micrometer 的 counter 与 timer 本身即为累计值，
 * 读一次即可得到「自进程启动至今」的分布，无需额外维护存储。
 * 代价是重启后归零，因此返回值中带有口径提示字段，由页面展示。
 *
 * <p>指标名在此处使用字面量而非引用 {@code BusinessMetrics} 的常量：Micrometer 的指标
 * 一经导出即成为外部契约（Prometheus 侧按名字抓取），读取方不应因该类的常量重命名而失效。
 * 两者不一致时表现为页面全为 0，因此新增指标需同时更新此处。
 */
@Service
@RequiredArgsConstructor
public class AiStatsServiceImpl implements AiStatsService {

    private static final String M_AI_CALL = "ainovel.ai.call";

    private static final String M_AI_CALL_FAILED = "ainovel.ai.call.failed";

    private static final String M_AI_CALL_DURATION = "ainovel.ai.call.duration";

    private static final String M_AI_DEGRADE = "ainovel.ai.degrade";

    private static final String M_CACHE_ACCESS = "ainovel.cache.access";

    private static final String M_SSE_STREAM = "ainovel.sse.stream";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 标签缺失时的占位值：Micrometer 允许指标不带某个标签，此时 getTag 返回 null */
    private static final String TAG_ABSENT = "-";

    private final MeterRegistry registry;

    private final DailyQuotaLimiter dailyQuotaLimiter;

    /**
     * 平台 Key 每日调用上限：与 {@code AiConfigServiceImpl} 读取同一配置项。
     *
     * <p>字段初始化器与配置项默认值保持一致：无 Spring 上下文的单测中 {@code @Value} 不生效，
     * 字段将停留在初始化值，若不留初始化器则为 0，剩余额度会恒为 0 且断言失去意义。
     */
    @Value("${app.ai-platform-daily-limit:100}")
    private int platformDailyLimit = 100;

    /** 文生图每日张数上限：与 {@code CoverServiceImpl} 读取同一配置项，初始化器理由同上 */
    @Value("${app.ai-cover-daily-limit:20}")
    private int coverDailyLimit = 20;

    @Override
    public AiStatsVO stats() {
        AiStatsVO vo = new AiStatsVO();
        vo.setGeneratedAt(LocalDateTime.now().format(TIME_FORMAT));
        vo.setCounterScope("调用次数与耗时自本进程启动累计，重启清零、多实例不合并；当日额度取自 Redis，跨天重置");

        List<AiStatsVO.SceneStat> scenes = scenes();
        vo.setScenes(scenes);
        vo.setTotals(totals(scenes));
        vo.setDegrade(degrade());
        vo.setCache(cache());
        vo.setSse(sse());
        vo.setQuota(quota());
        return vo;
    }

    /** 按「场景 + 模型」归组：成功数取自计数指标，耗时取自同名 Timer */
    private List<AiStatsVO.SceneStat> scenes() {
        Map<String, AiStatsVO.SceneStat> byKey = new LinkedHashMap<>();
        for (Counter counter : registry.find(M_AI_CALL).counters()) {
            sceneStat(byKey, counter).setSucceeded((long) counter.count());
        }
        for (Counter counter : registry.find(M_AI_CALL_FAILED).counters()) {
            AiStatsVO.SceneStat stat = sceneStat(byKey, counter);
            stat.setFailed(stat.getFailed() + (long) counter.count());
        }
        for (Timer timer : registry.find(M_AI_CALL_DURATION).timers()) {
            AiStatsVO.SceneStat stat = sceneStat(byKey, timer);
            long count = timer.count();
            stat.setAvgCostMs(count == 0 ? 0 : Math.round(timer.totalTime(TimeUnit.MILLISECONDS) / count));
            stat.setMaxCostMs(Math.round(timer.max(TimeUnit.MILLISECONDS)));
        }
        List<AiStatsVO.SceneStat> list = new ArrayList<>(byKey.values());
        list.sort(Comparator.comparingLong(AiStatsVO.SceneStat::getSucceeded).reversed());
        return list;
    }

    private static AiStatsVO.SceneStat sceneStat(Map<String, AiStatsVO.SceneStat> byKey, Meter meter) {
        String scene = tag(meter, "scene");
        String model = tag(meter, "model");
        return byKey.computeIfAbsent(scene + "|" + model, key -> {
            AiStatsVO.SceneStat stat = new AiStatsVO.SceneStat();
            stat.setScene(scene);
            stat.setModel(model);
            // 场景可能已从枚举中下线，此时沿用场景码，保证仍能看到这部分调用量
            AiScene sceneEnum = AiScene.fromCode(scene);
            stat.setLabel(sceneEnum == null ? scene : sceneEnum.label());
            return stat;
        });
    }

    private static AiStatsVO.Totals totals(List<AiStatsVO.SceneStat> scenes) {
        long succeeded = 0;
        long failed = 0;
        for (AiStatsVO.SceneStat stat : scenes) {
            succeeded += stat.getSucceeded();
            failed += stat.getFailed();
        }
        long attempts = succeeded + failed;
        AiStatsVO.Totals totals = new AiStatsVO.Totals();
        totals.setSucceeded(succeeded);
        totals.setFailed(failed);
        totals.setAttempts(attempts);
        totals.setSuccessRate(attempts == 0 ? 0 : (double) succeeded / attempts);
        return totals;
    }

    private List<AiStatsVO.DegradeStat> degrade() {
        List<AiStatsVO.DegradeStat> list = new ArrayList<>();
        for (Counter counter : registry.find(M_AI_DEGRADE).counters()) {
            AiStatsVO.DegradeStat stat = new AiStatsVO.DegradeStat();
            String scene = tag(counter, "scene");
            stat.setScene(scene);
            AiScene sceneEnum = AiScene.fromCode(scene);
            stat.setLabel(sceneEnum == null ? scene : sceneEnum.label());
            stat.setReason(tag(counter, "reason"));
            stat.setCount((long) counter.count());
            list.add(stat);
        }
        list.sort(Comparator.comparingLong(AiStatsVO.DegradeStat::getCount).reversed());
        return list;
    }

    /** 命中与未命中分列计数，此处按缓存名合并后算命中率 */
    private List<AiStatsVO.CacheStat> cache() {
        Map<String, long[]> byName = new LinkedHashMap<>();
        for (Counter counter : registry.find(M_CACHE_ACCESS).counters()) {
            long[] hitAndMiss = byName.computeIfAbsent(tag(counter, "cache"), key -> new long[2]);
            if ("hit".equals(tag(counter, "result"))) {
                hitAndMiss[0] += (long) counter.count();
            } else {
                hitAndMiss[1] += (long) counter.count();
            }
        }
        List<AiStatsVO.CacheStat> list = new ArrayList<>();
        byName.forEach((name, hitAndMiss) -> {
            AiStatsVO.CacheStat stat = new AiStatsVO.CacheStat();
            stat.setCache(name);
            stat.setHit(hitAndMiss[0]);
            stat.setMiss(hitAndMiss[1]);
            long reads = hitAndMiss[0] + hitAndMiss[1];
            stat.setHitRate(reads == 0 ? 0 : (double) hitAndMiss[0] / reads);
            list.add(stat);
        });
        list.sort(Comparator.comparingLong((AiStatsVO.CacheStat stat) -> stat.getHit() + stat.getMiss()).reversed());
        return list;
    }

    /** 流式接口的收尾结果：HTTP 层无法反映流是否读完，该指标是唯一信号 */
    private List<AiStatsVO.SseStat> sse() {
        List<AiStatsVO.SseStat> list = new ArrayList<>();
        for (Counter counter : registry.find(M_SSE_STREAM).counters()) {
            AiStatsVO.SseStat stat = new AiStatsVO.SseStat();
            stat.setApi(tag(counter, "api"));
            stat.setOutcome(tag(counter, "outcome"));
            stat.setCount((long) counter.count());
            list.add(stat);
        }
        list.sort(Comparator.comparing(AiStatsVO.SseStat::getApi)
                .thenComparing(AiStatsVO.SseStat::getOutcome));
        return list;
    }

    /**
     * 当日额度：只列全局的两项。
     *
     * <p>用户每日字数是按用户各自计数，管理端无法汇总为一个数字（逐用户读取等于遍历全部用户），
     * 因此不在此列出。
     */
    private List<AiStatsVO.QuotaStat> quota() {
        return List.of(
                quotaStat("平台 Key（按次）", AiQuotaConstant.PLATFORM_USAGE_KEY_PREFIX, platformDailyLimit),
                quotaStat("文生图（按张）", AiQuotaConstant.COVER_USAGE_KEY_PREFIX, coverDailyLimit));
    }

    private AiStatsVO.QuotaStat quotaStat(String name, String keyPrefix, long limit) {
        long used = dailyQuotaLimiter.currentUsage(keyPrefix);
        AiStatsVO.QuotaStat stat = new AiStatsVO.QuotaStat();
        stat.setName(name);
        stat.setUsed(used);
        stat.setLimit(limit);
        stat.setRemaining(Math.max(0, limit - used));
        return stat;
    }

    private static String tag(Meter meter, String key) {
        String value = meter.getId().getTag(key);
        return value == null ? TAG_ABSENT : value;
    }
}
