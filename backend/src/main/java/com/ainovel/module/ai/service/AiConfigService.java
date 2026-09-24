package com.ainovel.module.ai.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.domain.form.AiConfigForm;
import com.ainovel.module.ai.domain.form.UserAiKeyForm;
import com.ainovel.module.ai.domain.vo.UserAiConfigVO;

/**
 * AI 配置服务：动态配置（存储于 DB，单行 id=1），支持界面保存 key 后实时生效。
 *
 * <p>用户级配置每人一行 {@code t_user_ai_config}，自带 Key 优先，否则消耗平台免费额度
 * （额度按日重置，见 {@code ai:user:usage:*}）。
 */
public interface AiConfigService {

    /**
     * 获取生效配置（含完整 apiKey，仅供内部调用 AI 接口使用）。
     */
    public AiConfig getActiveConfig();

    /**
     * 返回给前端的配置（apiKey 脱敏）。
     */
    public AiConfig getConfigMasked();

    /**
     * 保存配置；apiKey 传入空值或全 * 时保留原 key。
     */
    public void saveConfig(AiConfigForm form);

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
     *
     * @param units 本次调用预计消耗的字数，由 {@link #estimateUnits} 估算
     */
    public AiConfig getActiveConfigForUser(Long userId, long units);

    /**
     * 估算一次调用要消耗多少字：送入模型的文本长度，低于最小计费单位时按最小计费单位算。
     *
     * <p>额度按**字**而非按 token 计算，是为了能向用户说明：「今天还剩 3 万字」比
     * 「还剩 12000 token」更有意义。而字数在调用**之前**即可算出（要送入模型的文本长度
     * 是已知的），因此「先扣后调」在该口径下预扣即为最终值，无需等响应返回后再结算。
     *
     * <p>最小计费单位（{@code app.ai-min-charge-units}）不可省略：起名、简介这类调用的可见输入
     * 仅几十字，纯按字数计算等同于不计费，3 万字可换取上千次调用，额度这一数值将失去意义。
     *
     * @param parts 本次要送入模型的各段文本（system 提示词、用户输入、正文…），可为 null
     * @return 计费字数，至少为最小计费单位
     */
    public int estimateUnits(String... parts);

    /**
     * 智能搜索生效配置：自带 Key 时不限次；否则使用平台 Key（仅受全局日上限约束，不扣个人额度）。
     *
     * <p>未登录（{@code userId == null}）等同于无自带 Key，使用平台 Key + 全局日上限；
     * 平台未配置 Key 时走 mock，不占上限。
     */
    public AiConfig getActiveConfigForSearch(Long userId);

    /**
     * 当前用户的 AI 配置（ownKey 脱敏），供「AI 设置」页展示额度与自带 Key。
     *
     * <p>用量与剩余均从**当日**计数器读取，因此下发的是「今天还剩多少字」：
     * 剩余量由服务端计算，前端直接展示，无需自行相减两个数值（避免口径变更后前端不一致）。
     */
    public UserAiConfigVO getUserConfigMasked(Long userId);

    /**
     * 保存用户自带配置：Key 及其配套的 endpoint / 模型。
     *
     * <p>Key 的三种输入语义：脱敏值（含 ****）为未修改、空串为清除、其余为新 Key。
     * endpoint / 模型仅在「清除」时一并清空，其余情况按提交值覆盖（留空即「沿用平台」）。
     *
     * <p>endpoint 必须可覆盖的原因：若仅替换 Key 而不替换地址，用户填写的通义千问 Key
     * 会被发送到平台的 DeepSeek 地址并返回 401，用户会认为「该网站异常」。
     */
    public void saveUserKey(Long userId, UserAiKeyForm form);

    /**
     * 管理员重置指定用户的免费额度：该功能已关闭。
     *
     * <p>管理员账号对外开放，恶意访客登录管理员后可通过该接口为自身或他人无限增加额度，
     * 因此后端直接拒绝（前端按钮亦置灰），双重保护。
     */
    public void resetUserQuota(Long userId);

    /**
     * 平台未配置 Key 时，是否允许返回示例内容（mock 降级）。
     *
     * <p>开启便于本地开发（无 Key 也可跑通流程与单测）；**生产环境应关闭**：
     * 以示例文本充当生成结果，其影响大于直接提示「暂不可用」，用户会认为内容确已生成。
     */
    public boolean isMockAllowed(AiConfig config);

    /**
     * 调用外部 AI 接口失败后归还额度：将「先扣后调」所扣额度退回，用户不应为失败付费。
     *
     * <p>仅在本次确实扣费（{@link AiConfig#getQuotaChargedUnits()}）时归还，且按原扣费
     * 字数原样退回：自带 Key、管理员、无用户上下文的调用本就未扣费，归还等同于凭空增加额度。
     */
    public void refundQuotaIfCharged(AiConfig config, Long userId);

    /**
     * 平台 Key 全局日用量硬上限：仅在未达上限时原子 INCR，超限返回 false（不占用）。
     *
     * <p>通过 Lua 保证「读-判-增」的原子性，计数不会越过 {@code platformDailyLimit}；
     * 无论用户注册多少个新账号，平台 Key 当日总调用次数均限制在硬上限内。
     * 生成/搜索超限时抛出 {@link ErrorCode#AI_PLATFORM_BUSY}；审核超限时由调用方降级（跳过 AI 审核，转人工）。
     */
    public boolean tryAcquirePlatformQuota();

    /**
     * 确认「本次确有模型可用」，否则抛出 {@link ErrorCode#AI_GENERATE_FAIL}。
     *
     * <p>供**不能降级**的功能使用（审查、润色这类）：平台未配置 Key 时，演示文字会被当作
     * 真实结果采纳，因此 mock 不视为可用。
     *
     * <p>需要该判断的原因：缺少它时，平台未配置 Key 的状态下审查会**实际请求上游**，
     * 随后每一章均失败：单章向作者返回「这次审查没能完成，请稍后再试」，
     * 全文审查则创建一个逐章失败的任务。作者只能反复重试，而重试不会成功。
     * 起名/简介/续写/润色四条链路早已各自判断，仅审查的两条遗漏（降级冒烟测试发现）。
     *
     * @param userId 当前用户；自带 Key 的用户照常可用。无登录上下文时传 null，
     *               此时仅检查平台配置（MQ 线程即为该情形）
     */
    public void requireModelAvailable(Long userId);
}
