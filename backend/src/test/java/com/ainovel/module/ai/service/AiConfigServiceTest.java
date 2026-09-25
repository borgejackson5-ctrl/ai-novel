package com.ainovel.module.ai.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.ratelimit.DailyQuotaLimiter;
import com.ainovel.module.ai.config.AiProperties;
import com.ainovel.module.ai.dao.AiConfigMapper;
import com.ainovel.module.ai.dao.UserAiConfigMapper;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.domain.entity.UserAiConfig;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.ai.service.impl.AiConfigServiceImpl;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 配置服务单测：额度重置已关闭 + 搜索自带 Key 路由
 */
@ExtendWith(MockitoExtension.class)
class AiConfigServiceTest {

    @Mock
    private AiConfigMapper aiConfigMapper;

    @Mock
    private UserAiConfigMapper userAiConfigMapper;

    // 这两个依赖若不 mock，@InjectMocks 会将其注入为 null，一旦有用例进入
    // 「无自带 Key → 走平台额度」或「读取 yaml 兜底配置」的分支就会 NPE。
    // 在此声明是为了让后续补充额度路径（涉及扣额度）的用例不会踩空。
    @Mock
    private AiProperties props;

    @Mock
    private DailyQuotaLimiter dailyQuotaLimiter;

    private AiConfigService service;

    @BeforeEach
    void initService() {
        service = new AiConfigServiceImpl(aiConfigMapper, userAiConfigMapper, props, dailyQuotaLimiter);
    }

    @Test
    @DisplayName("重置额度：演示环境已关闭 → 抛 AI_QUOTA_RESET_DISABLED")
    void resetUserQuota_disabled_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resetUserQuota(1L));
        assertEquals(ErrorCode.AI_QUOTA_RESET_DISABLED, ex.getErrorCode());
    }

    // ==================== 按字数计费（额度即成本，逐条约束口径） ====================

    @Test
    @DisplayName("估算计费字数：低于最小计费单位时按最小单位算（短文本调用不能白送）")
    void estimateUnits_belowMinimum_usesMinimum() {
        assertEquals(200, service.estimateUnits("写个书名"),
                "起名这类输入只有几十字，没有下限的话 3 万字能换上千次调用");
    }

    @Test
    @DisplayName("估算计费字数：多段累加，null 段跳过（长文审查按实际字数扣）")
    void estimateUnits_sumsParts() {
        assertEquals(3500, service.estimateUnits("x".repeat(1500), "y".repeat(2000), null));
        assertEquals(3200, service.estimateUnits("x".repeat(1200), null, "y".repeat(2000)),
                "null 段必须跳过而不是整段算 0 导致少扣");
    }

    @Test
    @DisplayName("按字数扣额度：扣的是本次字数，并记下扣了多少（失败时按这个数退）")
    void getActiveConfigForUser_chargesByUnits() {
        when(aiConfigMapper.selectById(1L)).thenReturn(platformConfig());
        when(userAiConfigMapper.selectOne(any())).thenReturn(userConfig(30000));
        when(dailyQuotaLimiter.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(true);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);

            AiConfig result = service.getActiveConfigForUser(5L, 3000);

            verify(dailyQuotaLimiter).tryAcquire("ai:user:usage:5:", 30000L, 3000L);
            assertEquals(3000L, result.getQuotaChargedUnits(),
                    "必须记下扣了多少字 —— 只记 boolean 的话失败时退多少全靠猜");
        }
    }

    @Test
    @DisplayName("额度不够这一次 → 抛 AI_QUOTA_EXHAUSTED，并归还刚占用的平台配额")
    void getActiveConfigForUser_notEnough_throws() {
        when(aiConfigMapper.selectById(1L)).thenReturn(platformConfig());
        when(userAiConfigMapper.selectOne(any())).thenReturn(userConfig(30000));
        // 分开 stub：平台总量按「次」判定（仍充足），不足的是用户自身的字数。
        // 笼统 stub 为 false 会先命中平台分支，抛出 AI_PLATFORM_BUSY，测到的是另一种情况。
        when(dailyQuotaLimiter.tryAcquire(startsWith("ai:platform:"), anyLong(), anyLong())).thenReturn(true);
        when(dailyQuotaLimiter.tryAcquire(startsWith("ai:user:"), anyLong(), anyLong())).thenReturn(false);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.getActiveConfigForUser(5L, 30000));
            assertEquals(ErrorCode.AI_QUOTA_EXHAUSTED, ex.getErrorCode());

            // 平台计数器记的是「实际发生的模型调用次数」，本分支一次模型都没调。
            // 不归还则每次重试都白吃一次全局配额，当日额度已用尽的账号反复请求即可
            // 把平台配额清空，此后所有用户都收到 AI_PLATFORM_BUSY
            verify(dailyQuotaLimiter).release("ai:platform:usage:", 1);
            // 用户额度这一次根本没占用成功，不能归还（否则凭空增加额度）
            verify(dailyQuotaLimiter, never()).release(startsWith("ai:user:usage:"), anyLong());
        }
    }

    @Test
    @DisplayName("退还额度：按当初扣掉的字数原样退回，不是固定还 1")
    void refundQuotaIfCharged_returnsExactUnits() {
        AiConfig config = new AiConfig();
        config.setQuotaChargedUnits(3000L);

        service.refundQuotaIfCharged(config, 5L);

        verify(dailyQuotaLimiter).release("ai:user:usage:5:", 3000L);
    }

    @Test
    @DisplayName("退还额度：没扣过（null / 0）时什么都不做，避免凭空空送额度")
    void refundQuotaIfCharged_noCharge_doesNothing() {
        service.refundQuotaIfCharged(new AiConfig(), 5L);
        AiConfig zero = new AiConfig();
        zero.setQuotaChargedUnits(0L);
        service.refundQuotaIfCharged(zero, 5L);

        verify(dailyQuotaLimiter, never()).release(anyString(), anyLong());
    }

    private AiConfig platformConfig() {
        AiConfig platform = new AiConfig();
        platform.setBaseUrl("http://ai.test/v1");
        platform.setApiKey("sk-platform");
        platform.setModel("deepseek-chat");
        return platform;
    }

    private UserAiConfig userConfig(int quotaLimit) {
        UserAiConfig uc = new UserAiConfig();
        uc.setUserId(5L);
        uc.setQuotaLimit(quotaLimit);
        return uc;
    }

    @Test
    @DisplayName("智能搜索：用户自带 Key → 用其 Key，不占平台上限")
    void getActiveConfigForSearch_byok_returnsOwnKey() {
        AiConfig platform = new AiConfig();
        platform.setBaseUrl("http://ai.test/v1");
        platform.setApiKey("sk-platform");
        platform.setModel("deepseek-chat");
        when(aiConfigMapper.selectById(1L)).thenReturn(platform);

        UserAiConfig uc = new UserAiConfig();
        uc.setUserId(5L);
        uc.setOwnKey("sk-own");
        when(userAiConfigMapper.selectOne(any())).thenReturn(uc);

        AiConfig result = service.getActiveConfigForSearch(5L);

        assertEquals("sk-own", result.getApiKey());
    }
}
