package com.ainovel.module.ai.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.AiConstant;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.ai.client.AiChatClient;
import com.ainovel.module.ai.domain.PolishMode;
import com.ainovel.module.ai.domain.WritingAvailability;
import com.ainovel.module.ai.domain.WritingLength;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.ai.service.AiWritingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 续写与润色的实现。
 *
 * <p>**三项约束决定了该类的结构**：
 * <ol>
 *   <li>输入为作者自己的正文：送入多少、扣除多少，两者必须由同一处计算（见 {@link #contextOf}）；</li>
 *   <li>输出会直接进入作者的稿件：因此仅在未配置 Key 时返回续写演示文字，**润色不返回假结果**
 *       （伪造的改写会被作者当作真实结果采纳，其影响大于明确提示「不可用」）；</li>
 *   <li>温度需区分：续写属创作，使用平台温度；润色属忠实改写，温度偏高时模型会改动剧情。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiWritingServiceImpl implements AiWritingService {

    /** 润色片段过短无意义：无可改写空间，且会被最小计费单位兜底为 200 字，作者会认为计费不合理 */
    private static final int MIN_POLISH_CHARS = 20;

    /**
     * 润色使用的固定温度。
     *
     * <p>不使用平台档位（默认 0.8）：该档位用于「起名/简介」这类创作，
     * 用于润色时模型会改动情节，作者的「保持原意」将无法保证。
     */
    private static final double POLISH_TEMPERATURE = 0.3;

    /**
     * 送入模型的上文长度（本章末尾多少字）。
     *
     * <p>带初始值而非仅依赖 {@code @Value}：无 Spring 上下文的单测中不会发生注入，
     * 字段会保持 0，此时「取末尾 0 字」会使每次续写都取不到上文。
     */
    @Value("${app.ai-write-context-chars:1200}")
    private int contextChars = 1200;

    private final AiChatClient aiClient;

    private final AiConfigService aiConfigService;

    private static final String CONTINUE_SYSTEM_PROMPT = """
            你是中文小说的续写助手。作者会给你一段已经写好的正文（这一章的结尾部分），
            你要顺着它的语气、人称与情节往下写一小段。

            硬性要求：
            - 只输出续写的正文本身。不要开场白（如「好的，我来续写」），不要小标题，不要解释你的思路。
            - 从原文最后一句往下接。不要重写原文，也不要把最后一句再写一遍。
            - 保持和原文一致的人称与视角（第三人称就第三人称，第一人称就第一人称），句子长短也贴近原文。
            - 分段方式贴近原文：原文分段你就分段，一段两三百字也正常；不要写成一整块不分段，
              也不要三五个字就换行。
            - 推进情节：写接下来的动作、对话或转折，不要停在原地做环境描写。
            - 不要在末尾写「未完待续」这类字样，也不要用省略号收尾。
            - 不要出现任何关于你自己是 AI 的表述。
            """;

    private static final String POLISH_SYSTEM_PROMPT = """
            你是中文小说的文字编辑，按作者指定的要求改写一段正文。

            硬性要求：
            - 只输出改写后的正文。不要输出原文，不要解释你改了什么，不要加小标题或序号。
            - 不改动情节：人物、事件、因果关系、对话的意思都不能变（「精简」是压缩表达，不是删情节）。
            - 保留原文的人称、视角与时间线。
            - 不要出现任何关于你自己是 AI 的表述。
            """;

    /** 三种改法各自的约束：目标不同，所需写入的限制也不同 */
    private static final Map<PolishMode, String> POLISH_INSTRUCTIONS = Map.of(
            PolishMode.EXPRESS, """
                    要求：保持原意不变，只调整措辞与句式。把不通顺、别扭、翻译腔的地方改自然，
                    把重复的用词换掉。字数与原文相当。""",
            PolishMode.COMPACT, """
                    要求：把啰嗦、重复、可有可无的修饰删掉，让句子更紧。信息一个都不能少，
                    情节不能删。字数要明显少于原文。""",
            PolishMode.VIVID, """
                    要求：在情节不变的前提下，把概括的地方换成具体的动作、感官与对话，让画面立起来。
                    不要堆形容词，不要用「仿佛」「宛如」这类比喻凑数。字数可以比原文多一些。""");

    /** 无 Key 时的续写演示文字：与作者情节明显不衔接，可直观识别为占位内容 */
    private static final String MOCK_CONTINUE = "（示例文字：当前未配置 AI 服务）他停在巷口，"
            + "听了一会儿雨声，才把刀从鞘里抽出半寸。远处有人点起了灯，亮了片刻，又灭了。";

    // ==================== 开流前的检查 ====================

    @Override
    public void requireContinueReady(AiConfig config) {
        if (availability(config) == WritingAvailability.UNAVAILABLE) {
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, AiConstant.AI_NOT_OPEN_MSG);
        }
    }

    @Override
    public void requirePolishReady(AiConfig config) {
        // 仅接受 REAL：演示文字同样被拒绝。演示文字与「不可用」并非同义：
        // 对续写而言演示文字可用（仅为一段建议），对润色则是致命的（会被当作真实改写结果采纳）
        if (availability(config) != WritingAvailability.REAL) {
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, AiConstant.AI_NOT_OPEN_MSG);
        }
    }

    /** 本次走哪条路径。仅在本类内使用，规则由两个 requireXxxReady 对外表达 */
    private WritingAvailability availability(AiConfig config) {
        if (StringUtils.hasText(config.getApiKey())) {
            return WritingAvailability.REAL;
        }
        return aiConfigService.isMockAllowed(config)
                ? WritingAvailability.MOCK
                : WritingAvailability.UNAVAILABLE;
    }

    // ==================== 额度估算 ====================

    @Override
    public int estimateContinueUnits(String content, String direction) {
        // 此处一并拦截「正文为空」：本方法在创建 SSE 之前被调用，
        // 参数问题应返回 400，而非一个已建立但永远取不到内容的流
        requireContext(content);
        return aiConfigService.estimateUnits(contextOf(content), direction);
    }

    @Override
    public int estimatePolishUnits(String content) {
        requirePolishable(content);
        return aiConfigService.estimateUnits(content);
    }

    // ==================== 续写 ====================

    @Override
    public void continueWriting(String content, String direction, WritingLength length,
                                AiConfig config, Consumer<String> onChunk) {
        String context = requireContext(content);
        WritingLength target = length == null ? WritingLength.MEDIUM : length;

        // 控制器已在开流前判断过一次；此处再次判断作为兜底，规则只定义在一处，不会出现两侧不一致
        requireContinueReady(config);
        if (availability(config) == WritingAvailability.MOCK) {
            log.info("AI 续写走 mock（平台未配置 Key），推送演示文字");
            pushCharByChar(MOCK_CONTINUE, onChunk);
            return;
        }

        String userPrompt = """
                【本章末尾的正文】（开头可能被截断，这是接续的起点）
                %s

                【接下来想写什么】
                %s

                请接着写大约 %d 字。只输出续写的正文。""".formatted(
                context,
                StringUtils.hasText(direction) ? direction.trim() : "没有特别要求，顺着上文自然往下写",
                target.targetChars());

        long start = System.currentTimeMillis();
        aiClient.chatStream(config.getBaseUrl(), config.getApiKey(), config.getModel(),
                temperatureOf(config), CONTINUE_SYSTEM_PROMPT, userPrompt, onChunk);
        log.info("AI 续写完成：上文 {} 字，目标 {} 字，耗时 {}ms",
                context.length(), target.targetChars(), System.currentTimeMillis() - start);
    }

    // ==================== 润色 ====================

    @Override
    public void polish(String content, PolishMode mode, AiConfig config, Consumer<String> onChunk) {
        requirePolishable(content);
        if (mode == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要改的方式");
        }

        // 润色不提供演示文字（理由见 requirePolishReady 上的说明），因此仅接受 REAL
        requirePolishReady(config);

        String userPrompt = """
                【改写要求】
                %s

                【原文】
                %s

                请按上面的要求改写这段文字，只输出改写后的正文。""".formatted(
                POLISH_INSTRUCTIONS.get(mode), content.strip());

        long start = System.currentTimeMillis();
        aiClient.chatStream(config.getBaseUrl(), config.getApiKey(), config.getModel(),
                POLISH_TEMPERATURE, POLISH_SYSTEM_PROMPT, userPrompt, onChunk);
        log.info("AI 润色完成：{}，原文 {} 字，耗时 {}ms",
                mode.label(), content.length(), System.currentTimeMillis() - start);
    }

    // ==================== 内部 ====================

    /**
     * 取送入模型的上文：本章末尾 {@code contextChars} 字。
     *
     * <p>**估算与实际调用必须走同一个方法。** 若分两处各写一遍，修改截取长度时会遗漏其一，
     * 导致扣费与实际送入内容静默不一致，界面上的「本次消耗」也不再可信。
     *
     * <p>开头被截断属正常情况（这是上文，不是整篇），提示词中已说明，避免模型将其当作新开头。
     */
    private String contextOf(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String text = content.strip();
        return text.length() <= contextChars ? text : text.substring(text.length() - contextChars);
    }

    private String requireContext(String content) {
        String context = contextOf(content);
        if (!StringUtils.hasText(context)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "先写点内容，再来续写");
        }
        return context;
    }

    private void requirePolishable(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "先选中要润色的文字");
        }
        if (content.strip().length() < MIN_POLISH_CHARS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "选中的内容太短了，至少选一段完整的话");
        }
    }

    private double temperatureOf(AiConfig config) {
        return config.getTemperature() == null ? 0.8 : config.getTemperature();
    }

    private void pushCharByChar(String text, Consumer<String> onChunk) {
        for (char c : text.toCharArray()) {
            onChunk.accept(String.valueOf(c));
            try {
                Thread.sleep(15);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
