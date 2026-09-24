package com.ainovel.module.ai.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.AiConstant;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.mq.MqSender;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.ai.client.AiChatClient;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.common.message.AiAuditMessage;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Novel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Map;
import java.util.function.Consumer;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.ai.service.AiService;

/**
 * AI 内容生成服务（生成书名/简介；平台未配置 Key 时按开关决定是否返回演示内容）。
 *
 * <p>**调用记录的变更**：本类原先还会向 {@code t_ai_generation_log} 插入一行（含 prompt 与 result 的**全文**）。
 * 该表已删除，理由有三：① 它只覆盖 6 条 AI 链路中的 2 条（起名/简介、智能搜索），
 * 审查与创作均不写入，「AI 调用审计」名不副实；② 它将用户输入与模型输出原文写入数据库，
 * 而本项目对调用记录的口径是**只记模型 / 耗时 / 字数，不记正文**（见 {@code AiClient.logCall}）；
 * ③ 字段中仍保留 {@code status} / {@code resultUrl} 这类文生图任务的遗留列。
 * 若需按调用量、耗时、降级率进行统计，查看应用日志（{@code AiClient.logCall}）即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiServiceImpl implements AiService {

    /**
     * 演示内容的标注。
     *
     * <p>**必须携带，且逐行携带。** 演示文字看起来与真实生成结果无异，
     * 不标注时作者会当作真实生成结果使用。降级冒烟测试曾发现：平台 Key 为空时
     * 「AI 起名」返回一列形似真实的书名、「AI 简介」返回一段完整文案，均无法看出为演示内容。
     * 续写侧演示文字自始即带标注（{@code MOCK_CONTINUE}），此处与之对齐。
     *
     * <p>标题需逐行标注的原因：前端取**第一行**作为书名（{@code Create.vue} 中先
     * {@code split('\n')} 再取 [0]）。若仅在开头标注一次，该标注会被当作书名，
     * 真正的候选书名均无法使用。
     */
    private static final String MOCK_MARK = "（示例文字：当前未配置 AI 服务）";

    private static final String MOCK_LINE_MARK = "（示例）";

    private final AiChatClient aiClient;

    private final AiConfigService aiConfigService;

    private final NovelMapper novelMapper;

    private final MqSender mqSender;

    private static final Map<String, String> SYSTEM_PROMPTS = Map.of(
            "TITLE", "你是一位网络小说起名助手，擅长根据题材创作有吸引力的书名。请根据用户提供的题材或灵感，创作 5 个小说书名，每个一行，简洁有画面感。",
            "INTRO", "你是一位小说文案编辑。请根据书名、题材或章节节选，撰写一段 150 字以内的小说简介，要求有代入感、能激发阅读欲望，不要剧透结局。"
    );

    /**
     * {@inheritDoc}
     *
     * <p>**计费只按用户输入估算，不含系统提示词。** 提示词是平台的固定成本，不随用户输入变化，
     * 转嫁给用户既不公平也解释不清。章节审查侧曾验证：正文 75 字，若将提示词计入需扣 539 字，
     * 作者会认为该功能计费不合理。
     *
     * <p>此处原先将 SYSTEM_PROMPTS 一并传入 {@code estimateUnits}，与审查侧存在两条口径。
     * 起名/简介因有最小计费单位（200 字）兜底，差额一直未暴露。现全站只有一条口径：
     * **计算用户可见的字数**。{@code checkType} 仍需调用，其保证非法类型不会进入取提示词的步骤。
     */
    public int estimateUnits(String type, String input) {
        checkType(type);
        return aiConfigService.estimateUnits(input);
    }

    /**
     * 提交 AI 审核：置为待审核 + 发送 MQ 异步审核。
     *
     * <p>投递必须走 {@link MqSender#sendAfterCommit}：这是全项目唯一的 MQ 出口。
     * 直接调用 {@code rabbitTemplate.convertAndSend} 会丢失 publisher-confirm 的
     * CorrelationData，broker 拒收时无从感知；且本方法**一旦加上
     * {@code @Transactional}**，直发即变为「事务尚未提交就发出消息」，
     * 消费者可能在数据库中读不到新状态。
     */
    public void submitAudit(Long novelId) {
        Novel novel = new Novel();
        novel.setId(novelId);
        novel.setAuditStatus(AuditStatusEnum.WAIT.getCode());
        novelMapper.updateById(novel);

        AiAuditMessage message = new AiAuditMessage();
        message.setNovelId(novelId);
        mqSender.sendAfterCommit(MqConstant.AI_EXCHANGE, MqConstant.AI_AUDIT_ROUTING_KEY, message);
        log.info("已提交审核消息: novelId={}", novelId);
    }

    /**
     * 统一生成入口。
     *
     * @param type  生成类型 TITLE/INTRO
     * @param input 用户输入（题材/书名等）
     */
    public String generate(String type, String input) {
        // 类型校验与额度估算均集中在 estimateUnits 内（其先校验 type 再取提示词）
        int units = estimateUnits(type, input);
        AiConfig config = aiConfigService.getActiveConfigForUser(LoginUserUtil.getUserId(), units);

        // 仅在平台未配置 Key 时才涉及 mock：开关开启则返回演示内容（供本地开发），关闭则明确提示不可用。
        // 以示例文本充当生成结果，其影响大于直接提示「暂不可用」，用户会认为内容确已生成。
        requireGenerateReady(config);

        try {
            if (isMock(config)) {
                return mockGenerate(type, input);
            }
            return aiClient.chat(config.getBaseUrl(), config.getApiKey(), config.getModel(),
                    config.getTemperature() == null ? 0.8 : config.getTemperature(),
                    SYSTEM_PROMPTS.get(type), input);
        } catch (RuntimeException e) {
            // 调用失败需退回刚扣除的额度，用户不应为一次失败付费
            aiConfigService.refundQuotaIfCharged(config, LoginUserUtil.getUserId());
            throw e;
        }
    }

    /**
     * 流式生成：逐段推送增量文本（SSE）。
     *
     * <p>无 Key 时以 mock 逐字推送模拟流式，保证无 Key 也可体验流式效果。
     *
     * @param type    生成类型 TITLE/INTRO
     * @param input   用户输入
     * @param config  已解析的生效配置（由调用方在创建 SSE 前同步解析并扣额度，额度耗尽时抛 JSON 错误）
     * @param onChunk 每收到一段增量文本时的回调
     */
    public void generateStream(String type, String input, AiConfig config, Consumer<String> onChunk) {
        checkType(type);
        requireGenerateReady(config);
        boolean useMock = isMock(config);

        if (useMock) {
            // mock 逐字推送，模拟打字机流式效果
            String result = mockGenerate(type, input);
            for (char c : result.toCharArray()) {
                onChunk.accept(String.valueOf(c));
                sleepQuietly(15);
            }
        } else {
            aiClient.chatStream(config.getBaseUrl(), config.getApiKey(), config.getModel(),
                    config.getTemperature() == null ? 0.8 : config.getTemperature(),
                    SYSTEM_PROMPTS.get(type), input, onChunk);
        }
    }

    /**
     * 校验生成类型，防止 SYSTEM_PROMPTS.get(type) 返回 null 引发 NPE
     */
    private void checkType(String type) {
        if (!SYSTEM_PROMPTS.containsKey(type)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "生成类型非法，仅支持 TITLE/INTRO");
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void requireGenerateReady(AiConfig config) {
        if (isMock(config) && !aiConfigService.isMockAllowed(config)) {
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, AiConstant.AI_NOT_OPEN_MSG);
        }
    }

    /** 本次是否走演示内容（平台未配置 Key）。判定口径仅此一处，三个调用点均使用它 */
    private boolean isMock(AiConfig config) {
        return !StringUtils.hasText(config.getApiKey());
    }

    private String mockGenerate(String type, String input) {
        return switch (type) {
            case "TITLE" -> MOCK_LINE_MARK + "凡人修仙之仙界篇\n"
                    + MOCK_LINE_MARK + "我在聊斋当县令\n"
                    + MOCK_LINE_MARK + "大周提刑官\n"
                    + MOCK_LINE_MARK + "山海寻仙录\n"
                    + MOCK_LINE_MARK + "重生之嫡女归来";
            case "INTRO" -> MOCK_MARK + "他一朝穿越，成了没落世家的庶子。旁人眼里的废物，却身怀一卷残破古书。当尘封的秘密被揭开，他才明白，这天下棋局，早已为他留了一子……";
            default -> MOCK_MARK + "输入：" + input;
        };
    }
}
