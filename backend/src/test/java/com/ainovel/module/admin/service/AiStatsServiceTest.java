package com.ainovel.module.admin.service;

import com.ainovel.common.enums.AiScene;
import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.common.ratelimit.DailyQuotaLimiter;
import com.ainovel.module.admin.domain.vo.AiStatsVO;
import com.ainovel.module.admin.service.impl.AiStatsServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI 用量统计的单测。
 *
 * <p>指标由真实的 {@link BusinessMetrics} 写入 {@code SimpleMeterRegistry}，而不是手工构造
 * counter：这样验证的是「埋点与读取用的是同一套指标名与标签」，而不只是「某段归组代码算对了」。
 * 指标名不一致时页面全为 0 且不抛异常，正需要这一类断言来发现。
 */
@DisplayName("AI 用量统计")
class AiStatsServiceTest {

    private SimpleMeterRegistry registry;

    private DailyQuotaLimiter limiter;

    private AiStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        limiter = mock(DailyQuotaLimiter.class);
        service = new AiStatsServiceImpl(registry, limiter);
    }

    private BusinessMetrics metrics() {
        return new BusinessMetrics(registry);
    }

    @Test
    @DisplayName("调用次数、失败与耗时按「场景 + 模型」归组")
    void scenesGroupBySceneAndModel() {
        BusinessMetrics metrics = metrics();
        metrics.aiCall(AiScene.REVIEW, "deepseek-chat", 1000);
        metrics.aiCall(AiScene.REVIEW, "deepseek-chat", 3000);
        metrics.aiCallFailed(AiScene.REVIEW, "deepseek-chat", "other");
        metrics.aiCall(AiScene.SEARCH, "qwen-plus", 100);

        AiStatsVO vo = service.stats();

        assertThat(vo.getScenes()).hasSize(2);
        AiStatsVO.SceneStat review = vo.getScenes().get(0);
        assertThat(review.getScene()).isEqualTo("REVIEW");
        assertThat(review.getLabel()).as("展示名由枚举提供，页面不必另维护一份映射").isEqualTo("章节审查");
        assertThat(review.getModel()).isEqualTo("deepseek-chat");
        assertThat(review.getSucceeded()).isEqualTo(2);
        assertThat(review.getFailed()).isEqualTo(1);
        assertThat(review.getAvgCostMs()).isEqualTo(2000);
        assertThat(review.getMaxCostMs()).isEqualTo(3000);

        assertThat(vo.getTotals().getSucceeded()).isEqualTo(3);
        assertThat(vo.getTotals().getFailed()).isEqualTo(1);
        assertThat(vo.getTotals().getAttempts()).isEqualTo(4);
        assertThat(vo.getTotals().getSuccessRate()).isCloseTo(0.75, within(0.001));
    }

    @Test
    @DisplayName("读取的指标名与 BusinessMetrics 写入的一致（不一致时页面全为 0 且不报错）")
    void metricNamesMatchBusinessMetrics() {
        BusinessMetrics metrics = metrics();
        metrics.aiCall(AiScene.TITLE, "m", 10);
        metrics.aiCallFailed(AiScene.TITLE, "m", "other");
        metrics.aiDegrade(AiScene.TITLE, "no_key");
        metrics.cacheAccess("novel:detail", false);
        metrics.sseStream("generate", "error");

        AiStatsVO vo = service.stats();

        assertThat(vo.getTotals().getSucceeded()).isEqualTo(1);
        assertThat(vo.getTotals().getFailed()).isEqualTo(1);
        assertThat(vo.getDegrade()).hasSize(1);
        assertThat(vo.getDegrade().get(0).getReason()).isEqualTo("no_key");
        assertThat(vo.getCache()).hasSize(1);
        assertThat(vo.getSse()).hasSize(1);
        assertThat(vo.getSse().get(0).getOutcome()).isEqualTo("error");
    }

    @Test
    @DisplayName("展示名随码一并下发：原始码保持与指标标签一致，页面无需另维护映射")
    void displayLabelsAreResolved() {
        BusinessMetrics metrics = metrics();
        metrics.aiDegrade(AiScene.REVIEW, "local_fallback");
        metrics.cacheAccess("novel:chapter:content", false);
        metrics.cacheAccess("novel:chapter:page", true);
        metrics.sseStream("polish", "cancelled");

        AiStatsVO vo = service.stats();

        AiStatsVO.DegradeStat degrade = vo.getDegrade().get(0);
        assertThat(degrade.getReason()).as("码不变").isEqualTo("local_fallback");
        assertThat(degrade.getReasonLabel()).isEqualTo("改用本地处理");

        AiStatsVO.CacheStat content = vo.getCache().stream()
                .filter(stat -> "novel:chapter:content".equals(stat.getCache()))
                .findFirst().orElseThrow();
        assertThat(content.getLabel()).isEqualTo("章节正文");
        AiStatsVO.CacheStat page = vo.getCache().stream()
                .filter(stat -> "novel:chapter:page".equals(stat.getCache()))
                .findFirst().orElseThrow();
        assertThat(page.getLabel()).isEqualTo("章节目录");

        AiStatsVO.SseStat sse = vo.getSse().get(0);
        assertThat(sse.getApiLabel()).isEqualTo("文字润色");
        assertThat(sse.getOutcomeLabel()).isEqualTo("用户停止");
    }

    @Test
    @DisplayName("未登记的取值回落为码本身：新增缓存或新入口不会在页面上显示为空白")
    void unknownLabelFallsBackToCode() {
        BusinessMetrics metrics = metrics();
        metrics.aiDegrade(AiScene.REVIEW, "brand_new_reason");
        metrics.cacheAccess("novel:brand:new", false);
        metrics.sseStream("publish", "paused");

        AiStatsVO vo = service.stats();

        assertThat(vo.getDegrade().get(0).getReasonLabel()).isEqualTo("brand_new_reason");
        assertThat(vo.getCache().get(0).getLabel()).isEqualTo("novel:brand:new");
        assertThat(vo.getSse().get(0).getApiLabel()).isEqualTo("publish");
        assertThat(vo.getSse().get(0).getOutcomeLabel()).isEqualTo("paused");
    }

    @Test
    @DisplayName("缓存命中率由命中与未命中合并算出")
    void cacheHitRate() {
        BusinessMetrics metrics = metrics();
        metrics.cacheAccess("novel:detail", true);
        metrics.cacheAccess("novel:detail", true);
        metrics.cacheAccess("novel:detail", true);
        metrics.cacheAccess("novel:detail", false);

        AiStatsVO.CacheStat stat = service.stats().getCache().get(0);

        assertThat(stat.getCache()).isEqualTo("novel:detail");
        assertThat(stat.getHit()).isEqualTo(3);
        assertThat(stat.getMiss()).isEqualTo(1);
        assertThat(stat.getHitRate()).isCloseTo(0.75, within(0.001));
    }

    @Test
    @DisplayName("token 用量按「场景 + 模型」汇总，并与调用次数分列")
    void tokensGroupBySceneAndModel() {
        BusinessMetrics metrics = metrics();
        metrics.aiCall(AiScene.TITLE, "m", 10);
        metrics.aiTokens(AiScene.TITLE, "m", 1200, 300);
        metrics.aiTokens(AiScene.TITLE, "m", 800, 100);
        metrics.aiTokens(AiScene.INTRO, "m", 500, 200);

        AiStatsVO vo = service.stats();

        AiStatsVO.SceneStat title = vo.getScenes().stream()
                .filter(stat -> AiScene.TITLE.code().equals(stat.getScene()))
                .findFirst().orElseThrow();
        assertThat(title.getPromptTokens()).isEqualTo(2000);
        assertThat(title.getCompletionTokens()).isEqualTo(400);

        assertThat(vo.getTotals().getPromptTokens()).isEqualTo(2500);
        assertThat(vo.getTotals().getCompletionTokens()).isEqualTo(600);
    }

    @Test
    @DisplayName("上游未返回用量时仍计入调用次数，token 保持 0 —— 用量不能由次数推算")
    void tokensAbsentKeepsCallCount() {
        BusinessMetrics metrics = metrics();
        metrics.aiCall(AiScene.TITLE, "m", 10);

        AiStatsVO vo = service.stats();

        assertThat(vo.getScenes().get(0).getSucceeded()).isEqualTo(1);
        assertThat(vo.getScenes().get(0).getPromptTokens()).isZero();
        assertThat(vo.getScenes().get(0).getCompletionTokens()).isZero();
        assertThat(vo.getTotals().getPromptTokens()).isZero();
    }

    @Test
    @DisplayName("当日额度取 Redis 计数，超额时剩余显示 0 而非负数")
    void quotaComesFromRedisUsage() {
        when(limiter.currentUsage(anyString())).thenReturn(120L);

        AiStatsVO vo = service.stats();

        assertThat(vo.getQuota()).hasSize(2);
        AiStatsVO.QuotaStat platform = vo.getQuota().get(0);
        assertThat(platform.getUsed()).isEqualTo(120);
        assertThat(platform.getLimit()).isEqualTo(100);
        assertThat(platform.getRemaining()).as("剩余额度为负数无法展示，下限取 0").isZero();
    }

    @Test
    @DisplayName("无任何指标时不抛异常：首次启动与当日无人使用都会走到这里")
    void emptyMetricsIsSafe() {
        AiStatsVO vo = service.stats();

        assertThat(vo.getTotals().getAttempts()).isZero();
        assertThat(vo.getScenes()).isEmpty();
        assertThat(vo.getCache()).isEmpty();
        assertThat(vo.getQuota()).as("额度来自 Redis，与指标无关，因此仍有数据").hasSize(2);
        assertThat(vo.getStartedAt()).as("页面以启动时间标注统计起点").isNotBlank();
    }

    @Test
    @DisplayName("指标里出现枚举已下线的场景码时沿用场景码，不丢弃这行数据")
    void unknownSceneFallsBackToCode() {
        registry.counter("ainovel.ai.call", "scene", "LEGACY_X", "model", "m").increment();

        AiStatsVO.SceneStat stat = service.stats().getScenes().get(0);

        assertThat(stat.getScene()).isEqualTo("LEGACY_X");
        assertThat(stat.getLabel()).isEqualTo("LEGACY_X");
    }
}
