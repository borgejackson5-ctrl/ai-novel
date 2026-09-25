package com.ainovel.module.ai.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.AiConstant;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.ratelimit.DailyQuotaLimiter;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.ai.config.AiProperties;
import com.ainovel.module.ai.dao.AiConfigMapper;
import com.ainovel.module.ai.dao.UserAiConfigMapper;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.domain.entity.UserAiConfig;
import com.ainovel.module.ai.domain.form.AiConfigForm;
import com.ainovel.module.ai.domain.form.UserAiKeyForm;
import com.ainovel.module.ai.domain.vo.UserAiConfigVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ainovel.module.ai.service.AiConfigService;

/**
 * AI 配置服务：动态配置（存储于 DB，单行 id=1），支持界面保存 key 后实时生效。
 *
 * <p>用户级配置每人一行 {@code t_user_ai_config}，自带 Key 优先，否则消耗平台免费额度。
 * 额度**按字数**计（每天 3 万字），按日重置，见 {@code ai:user:usage:*}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigServiceImpl implements AiConfigService {

    private static final long CONFIG_ID = 1L;

    /**
     * 新用户默认平台免费额度：每天 3 万字（≈ 10 章正文），按日重置。
     *
     * <p>该数值对应「一个用户一天能审查多少内容」的量级，而非「能点击几次按钮」：
     * 起名、简介这类调用按最小计费单位扣除，一章三千字的审查按实际字数扣除。
     */
    private static final int DEFAULT_QUOTA = 30_000;

    /**
     * 单次调用的最小计费字数。
     *
     * <p>设置下限的原因：起名、简介这类调用的可见输入仅几十字，纯按字数计算等同于不计费，
     * 3 万字可换取上千次调用。真正限制成本的是平台全局日上限（{@code app.ai-platform-daily-limit}），
     * 但其与用户额度是两回事：用户额度需在界面上说明「今日大致可使用多少次」，
     * 因此需为每次调用设置下限。默认 200 字，即短文本类调用约 150 次/天。
     *
     * <p>字段带初始值为有意设计：{@code @Value} 在无 Spring 上下文的单测中不生效，
     * 若无初始值，单测中该值会静默为 0（下限形同不存在），断言无法验证真实行为。
     */
    @Value("${app.ai-min-charge-units:200}")
    private int minChargeUnits = 200;

    /** 平台 Key 全局日用量硬上限 key 前缀（按日期滚动） */
    private static final String PLATFORM_USAGE_KEY_PREFIX = "ai:platform:usage:";

    /** 用户免费额度计数器 key 前缀（按日期滚动，跨天自动清零） */
    private static final String USER_USAGE_KEY_PREFIX = "ai:user:usage:";

    /**
     * 额度用尽的提示：直接说明「今日已用完、明日恢复」，**不引导用户配置 Key**。
     * 读者不了解 API Key 的概念，展示运维层面的概念只会使其无从判断。
     */
    private static final String QUOTA_EXHAUSTED_MSG = "今天的免费字数已经用完了，明天再来试试";

    /** 平台当日总量触顶：与「你的额度用完」区分，避免用户误认为是自身额度问题 */
    private static final String PLATFORM_BUSY_MSG = "现在使用的人有点多，稍后再试试";

    private final AiConfigMapper aiConfigMapper;

    private final UserAiConfigMapper userAiConfigMapper;

    private final AiProperties props;

    private final DailyQuotaLimiter dailyQuotaLimiter;

    /** 平台 Key 每日调用硬上限（可配置，防止通过批量注册账号无偿使用平台 Key） */
    @Value("${app.ai-platform-daily-limit:100}")
    private int platformDailyLimit;

    /**
     * 获取生效配置（含完整 apiKey，仅供内部调用 AI 接口使用）
     */
    public AiConfig getActiveConfig() {
        AiConfig db = aiConfigMapper.selectById(CONFIG_ID);
        if (db == null) {
            // 回退到配置文件默认值
            AiConfig fallback = new AiConfig();
            fallback.setBaseUrl(props.getBaseUrl());
            fallback.setApiKey(props.getApiKey());
            fallback.setModel(props.getModel());
            fallback.setTemperature(props.getTemperature());
            fallback.setMockEnabled(props.getMockEnabled() ? 1 : 0);
            return fallback;
        }
        return db;
    }

    /**
     * 返回给前端的配置（apiKey 脱敏）
     */
    public AiConfig getConfigMasked() {
        AiConfig config = getActiveConfig();
        String key = config.getApiKey();
        if (StringUtils.hasText(key) && key.length() > 8) {
            config.setApiKey(key.substring(0, 4) + "****" + key.substring(key.length() - 4));
        } else if (StringUtils.hasText(key)) {
            config.setApiKey("****");
        }
        return config;
    }

    /**
     * 保存配置；apiKey 传空或全 * 时保留原 key
     */
    public void saveConfig(AiConfigForm form) {
        AiConfig existing = aiConfigMapper.selectById(CONFIG_ID);
        AiConfig config = existing == null ? new AiConfig() : existing;
        config.setId(CONFIG_ID);
        config.setBaseUrl(form.getBaseUrl());
        config.setModel(form.getModel());
        config.setTemperature(form.getTemperature());
        config.setMockEnabled(form.getMockEnabled() ? 1 : 0);

        // key 处理：传入脱敏值(含****)或 null 表示未修改，保留原 key；传入空串或新值则更新/清空
        String newKey = form.getApiKey();
        if (newKey == null || newKey.contains("****")) {
            // 未修改，保留原值
        } else {
            config.setApiKey(newKey.trim());
        }
        if (existing == null && config.getApiKey() == null) {
            config.setApiKey("");
        }

        if (existing == null) {
            aiConfigMapper.insert(config);
        } else {
            aiConfigMapper.updateById(config);
        }
    }

    // ==================== 用户级配置（BYOK + 免费额度） ====================

    /**
     * 解析指定用户生效的 AI 配置（用于正文生成，含额度扣减）。
     *
     * <p>路由规则：
     * <ol>
     *   <li>无用户上下文（内部/MQ 调用）：直接使用平台 Key，不扣额度、不占全局上限；</li>
     *   <li>管理员：使用平台 Key，不扣个人额度，但平台已配置 Key 时仍受全局日上限约束
     *       （管理员账号对外开放，用于防止无偿调用）；</li>
     *   <li>用户自带 Key：使用其 Key，不限次；</li>
     *   <li>其余情况使用平台 Key，先通过全局日上限，再按**字数**扣当日免费额度，
     *       额度不足时抛出 {@link ErrorCode#AI_QUOTA_EXHAUSTED}（额度按日重置）。</li>
     * </ol>
     * 平台未配置 Key 时走 mock，不扣额度、不占全局上限。
     */
    public AiConfig getActiveConfigForUser(Long userId, long units) {
        AiConfig platform = getActiveConfig();
        // 无用户上下文：内部/MQ 调用，直接使用平台 Key，不扣额度
        if (userId == null) {
            return platform;
        }
        // 管理员：使用平台 Key，不扣个人额度；但平台已配置 Key 时同样通过全局日上限（防止公开的 admin 账号被访客无偿使用）
        if (LoginUserUtil.isAdmin()) {
            if (StringUtils.hasText(platform.getApiKey()) && !tryAcquirePlatformQuota()) {
                throw new BusinessException(ErrorCode.AI_PLATFORM_BUSY, PLATFORM_BUSY_MSG);
            }
            return platform;
        }

        UserAiConfig uc = getUserConfig(userId);
        // 1. 用户自带 Key：不限次，并且它配套的 endpoint / 模型一起生效
        if (StringUtils.hasText(uc.getOwnKey())) {
            platform.setApiKey(uc.getOwnKey());
            // 完整 BYOK：用户填写了服务地址即须使用其地址。若仅替换 Key 而不替换地址，
            // 其他服务商的 Key 会请求到平台服务地址并返回 401，
            // 界面上「常用兼容服务地址」一栏也将失去作用。
            if (StringUtils.hasText(uc.getBaseUrl())) {
                platform.setBaseUrl(uc.getBaseUrl());
            }
            if (StringUtils.hasText(uc.getModel())) {
                platform.setModel(uc.getModel());
            }
            return platform;
        }
        // 2. 平台无 Key：mock 降级，不扣额度
        if (!StringUtils.hasText(platform.getApiKey())) {
            return platform;
        }
        // 3. 使用平台 Key：先通过全局日用量硬上限（防止通过批量注册账号无偿使用平台 Key），
        //    再扣用户当日免费额度。用户额度同样使用 Redis 日键，跨天自动清零，
        //    无需额外存储「上次重置日期」字段，也不会出现「昨日剩余额度累积至今日」这种与「每日 N 字」不符的情况。
        if (!tryAcquirePlatformQuota()) {
            throw new BusinessException(ErrorCode.AI_PLATFORM_BUSY, PLATFORM_BUSY_MSG);
        }
        int quotaLimit = uc.getQuotaLimit() == null ? DEFAULT_QUOTA : uc.getQuotaLimit();
        if (dailyQuotaLimiter.tryAcquire(USER_USAGE_KEY_PREFIX + userId + ":", quotaLimit, units)) {
            // 记录「本次扣费字数」：外部接口调用失败时需按该数值原样退回
            platform.setQuotaChargedUnits(units);
            return platform;
        }
        // 用户额度不足时归还刚才占用的平台配额。平台计数器记录的是「实际发生的模型调用次数」，
        // 而此处尚未调用模型：不归还则该次请求白白消耗一次全局配额，当日额度已用尽的账号
        // 反复重试即可把平台配额耗尽，此后所有用户都收到「AI 服务繁忙」。
        // 归还与占用之间非严格原子，但归还方向偏保守（最多减至 0，不会放大配额）。
        dailyQuotaLimiter.release(PLATFORM_USAGE_KEY_PREFIX, 1);
        throw new BusinessException(ErrorCode.AI_QUOTA_EXHAUSTED, QUOTA_EXHAUSTED_MSG);
    }

    /**
     * 智能搜索生效配置：自带 Key 时不限次；否则使用平台 Key（仅受全局日上限约束，不扣个人额度）。
     *
     * <p>未登录（{@code userId == null}）等同于无自带 Key，使用平台 Key + 全局日上限；
     * 平台未配置 Key 时走 mock，不占上限。
     */
    public AiConfig getActiveConfigForSearch(Long userId) {
        AiConfig platform = getActiveConfig();
        if (userId != null) {
            UserAiConfig uc = getUserConfigOrNull(userId);
            if (uc != null && StringUtils.hasText(uc.getOwnKey())) {
                platform.setApiKey(uc.getOwnKey());
                // 与 AI 生成保持一致：自带 Key 配套的 endpoint / 模型一并生效，
                // 否则会出现「已配置自有 Key，但搜索仍消耗平台额度」的困惑
                if (StringUtils.hasText(uc.getBaseUrl())) {
                    platform.setBaseUrl(uc.getBaseUrl());
                }
                if (StringUtils.hasText(uc.getModel())) {
                    platform.setModel(uc.getModel());
                }
                return platform;
            }
        }
        if (StringUtils.hasText(platform.getApiKey()) && !tryAcquirePlatformQuota()) {
            throw new BusinessException(ErrorCode.AI_PLATFORM_BUSY, PLATFORM_BUSY_MSG);
        }
        return platform;
    }

    /**
     * 当前用户的 AI 配置（ownKey 脱敏），供「AI 设置」页展示额度与自带 Key。
     *
     * <p>用量与剩余均从**当日**计数器读取，因此下发的是「今天还剩多少字」：
     * 剩余量由服务端计算，前端直接展示，无需自行相减两个数值（避免口径变更后前端不一致）。
     */
    public UserAiConfigVO getUserConfigMasked(Long userId) {
        UserAiConfig uc = getUserConfig(userId);
        UserAiConfigVO vo = new UserAiConfigVO();
        vo.setOwnKey(maskKey(uc.getOwnKey()));
        vo.setHasOwnKey(StringUtils.hasText(uc.getOwnKey()));
        int limit = uc.getQuotaLimit() == null ? DEFAULT_QUOTA : uc.getQuotaLimit();
        long used = dailyQuotaLimiter.currentUsage(USER_USAGE_KEY_PREFIX + userId + ":");
        vo.setQuotaLimit(limit);
        vo.setUsedUnits(used);
        vo.setRemainingUnits(Math.max(0, limit - used));
        // 自带 Key 配套的 endpoint / 模型：原样回显，便于用户继续修改
        vo.setBaseUrl(uc.getBaseUrl());
        vo.setModel(uc.getModel());
        // 平台当前值：供前端作为「留空则沿用本站」的占位提示（不含 Key，可下发）
        AiConfig platform = getActiveConfig();
        vo.setPlatformBaseUrl(platform.getBaseUrl());
        vo.setPlatformModel(platform.getModel());
        return vo;
    }

    /**
     * 保存用户自带配置：Key 及其配套的 endpoint / 模型。
     *
     * <p>Key 的三种输入语义：脱敏值（含 ****）为未修改、空串为清除、其余为新 Key。
     * endpoint / 模型仅在「清除」时一并清空，其余情况按提交值覆盖（留空即「沿用平台」）。
     *
     * <p>endpoint 必须可覆盖的原因：若仅替换 Key 而不替换地址，用户填写的通义千问 Key
     * 会被发送到平台的 DeepSeek 地址并返回 401，用户会认为「该网站异常」。
     */
    public void saveUserKey(Long userId, UserAiKeyForm form) {
        UserAiConfig uc = getUserConfig(userId);
        String rawKey = form.getApiKey();
        // 含 **** 说明前端回显的是脱敏值，用户未修改 Key 输入框（可能仅在修改地址/模型）
        boolean keyUnchanged = rawKey == null || rawKey.contains("****");
        boolean clearAll = !keyUnchanged && rawKey.trim().isEmpty();

        LambdaUpdateWrapper<UserAiConfig> update = new LambdaUpdateWrapper<UserAiConfig>()
                .eq(UserAiConfig::getId, uc.getId());

        if (clearAll) {
            // 清除自带配置：Key 与 endpoint / 模型一并清空，整体回到平台
            update.set(UserAiConfig::getOwnKey, null)
                    .set(UserAiConfig::getBaseUrl, null)
                    .set(UserAiConfig::getModel, null);
        } else {
            if (!keyUnchanged) {
                update.set(UserAiConfig::getOwnKey, rawKey.trim());
            }
            update.set(UserAiConfig::getBaseUrl, trimToNull(form.getBaseUrl()))
                    .set(UserAiConfig::getModel, trimToNull(form.getModel()));
        }
        userAiConfigMapper.update(null, update);
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 管理员重置指定用户的免费额度：该功能已关闭。
     *
     * <p>管理员账号对外开放，恶意访客登录管理员后可通过该接口为自身或他人无限增加额度，
     * 因此后端直接拒绝（前端按钮亦置灰），双重保护。
     */
    public void resetUserQuota(Long userId) {
        throw new BusinessException(ErrorCode.AI_QUOTA_RESET_DISABLED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>仅判断「是否配置 Key」，**不判断 mock 开关**：mock 返回的是演示文字，
     * 对于审查/润色这类结果会被当作真实结论采纳的功能，不构成可用状态。规则与
     * {@code AiWritingServiceImpl.requirePolishReady} 一致。
     */
    @Override
    public void requireModelAvailable(Long userId) {
        if (StringUtils.hasText(getActiveConfig().getApiKey())) {
            return;
        }
        if (userId != null) {
            UserAiConfig uc = getUserConfigOrNull(userId);
            if (uc != null && StringUtils.hasText(uc.getOwnKey())) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, AiConstant.AI_NOT_OPEN_MSG);
    }

    /**
     * 平台未配置 Key 时，是否允许返回示例内容（mock 降级）。
     *
     * <p>开启便于本地开发（无 Key 也可跑通流程与单测）；**生产环境应关闭**：
     * 以示例文本充当生成结果，其影响大于直接提示「暂不可用」，用户会认为内容确已生成。
     * （即便开启，示例内容也必须**带标注**，见 {@code AiServiceImpl.mockGenerate}。）
     */
    public boolean isMockAllowed(AiConfig config) {
        return config != null && config.getMockEnabled() != null && config.getMockEnabled() == 1;
    }

    /**
     * 本次调用未能交付时归还额度：将「先扣后调」所扣额度退回，用户不应为未取得的内容付费。
     *
     * <p>两种情形均走此分支：**生成失败**，以及**流超时**（用户等待五分钟未取得任何内容）。
     * 例外为「用户主动点击停止」：该情形不退回（界面已注明，且成本确已产生），
     * 由调用方判断，见 {@code StreamCancelledException#isRefundable()}。
     *
     * <p>仅在本次确实扣费（{@link AiConfig#getQuotaChargedUnits()}）时归还，且按原扣费
     * 字数原样退回：自带 Key、管理员、无用户上下文的调用本就未扣费，归还等同于凭空增加额度。
     */
    public void refundQuotaIfCharged(AiConfig config, Long userId) {
        Long charged = (userId == null || config == null) ? null : config.getQuotaChargedUnits();
        if (charged == null || charged <= 0) {
            return;
        }
        dailyQuotaLimiter.release(USER_USAGE_KEY_PREFIX + userId + ":", charged);
        // 使用「未完成」而非「调用失败」：该退款路径也覆盖**超时**
        // （用户等待过久、未取得任何内容），其并非"AI 接口调不通"。
        // 文案的差异会显著影响排查方向
        log.warn("本次 AI 调用未完成，已归还 {} 字免费额度: userId={}", charged, userId);
    }

    /**
     * {@inheritDoc}
     */
    public int estimateUnits(String... parts) {
        int chars = 0;
        if (parts != null) {
            for (String part : parts) {
                if (part != null) {
                    chars += part.length();
                }
            }
        }
        return Math.max(chars, minChargeUnits);
    }

    /**
     * 平台 Key 全局日用量硬上限：仅在未达上限时原子 INCR，超限返回 false（不占用）。
     *
     * <p>通过 Lua 保证「读-判-增」的原子性，计数不会越过 {@code platformDailyLimit}；
     * 无论用户注册多少个新账号，平台 Key 当日总调用次数均限制在硬上限内。
     * 生成/搜索超限时抛出 {@link ErrorCode#AI_PLATFORM_BUSY}；审核超限时由调用方降级（跳过 AI 审核，转人工）。
     */
    public boolean tryAcquirePlatformQuota() {
        // 平台上限按「次」计（权重 1）：其约束的是平台 Key 的调用次数，与用户扣除的字数无关
        return dailyQuotaLimiter.tryAcquire(PLATFORM_USAGE_KEY_PREFIX, platformDailyLimit, 1);
    }

    /**
     * 读取用户 AI 配置行（无配置行时返回 null，**不首次创建**）。
     * 供搜索等「只读配置、不消耗个人额度」的场景使用，避免为纯搜索创建额度行。
     */
    private UserAiConfig getUserConfigOrNull(Long userId) {
        return userAiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
    }

    /**
     * 读取用户配置，不存在时首次创建（额度默认为 {@link #DEFAULT_QUOTA} 字，无自带 Key）。
     * 并发首次创建由 {@code uk_user_id} 唯一索引兜底，冲突后重新查询。
     */
    private UserAiConfig getUserConfig(Long userId) {
        UserAiConfig uc = userAiConfigMapper.selectOne(
                new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        if (uc != null) {
            return uc;
        }
        UserAiConfig fresh = new UserAiConfig();
        fresh.setUserId(userId);
        fresh.setUsedCount(0);
        fresh.setQuotaLimit(DEFAULT_QUOTA);
        try {
            userAiConfigMapper.insert(fresh);
            return fresh;
        } catch (DuplicateKeyException e) {
            // 并发首次创建：唯一索引冲突，重新查询
            return userAiConfigMapper.selectOne(
                    new LambdaQueryWrapper<UserAiConfig>().eq(UserAiConfig::getUserId, userId));
        }
    }

    private String maskKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        if (key.length() > 8) {
            return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
        }
        return "****";
    }
}
