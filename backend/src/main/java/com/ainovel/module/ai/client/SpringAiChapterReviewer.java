package com.ainovel.module.ai.client;

import com.ainovel.module.ai.config.AiProperties;
import com.ainovel.module.ai.domain.ChapterReviewReport;
import com.ainovel.module.ai.tool.ChapterReviewTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Spring AI 实现：工具调用 + 循环 + 结构化输出。
 *
 * <p>工具调用循环由本类自行驱动，不使用框架的内部执行：Spring AI 默认由
 * {@code OpenAiChatModel} 的内部循环执行「调用工具 → 将结果再次提交模型」直至模型不再要求工具，
 * 但该循环**没有轮数上限**，也没有总时长预算，模型只要持续要求调用工具，框架就会一直执行。
 * 流式接口上另有 {@code SseEmitter} 超时兜底，而单章审查是**同步 HTTP 接口**，
 * 该请求会一直占用 Tomcat 线程，既不返回也不断开。
 * 因此此处关闭框架的内部执行（{@code internalToolExecutionEnabled(false)}），
 * 自行实现该循环，并补齐三项限制：
 *
 * <ol>
 *   <li>单次调用超时：仍由 {@code AiChatFactory} 的 readTimeout 兜底（{@code ai.timeout-seconds}）；</li>
 *   <li>**最大轮数**：{@code ai.review-max-tool-rounds}，默认 5，与提示词中
 *       「一章查 2 次左右，最多 3~5 次」对齐；</li>
 *   <li>**总时长预算**：{@code ai.review-budget-seconds}，默认 120 秒，
 *       用于覆盖「每轮耗时都很长」的情况。</li>
 * </ol>
 *
 * <p>**触发限制后不抛异常，而是收尾**：丢弃该轮要求调用的工具不执行，追加一条
 * 「现在给结论」的指令后再次调用（该次调用不携带任何工具，模型无可用工具，只能输出 JSON）。
 * 理由有两条：① 前面各轮已消耗的费用与已取得的材料不应作废；② 提示词本就按
 * 「查到什么算什么」设计。其代价是结果不完整，因此在 summary 前**由代码**附加一句
 * {@code （本次跨章核对提前中止，结果不完整）}，不依赖模型自行记录：
 * 「未审全」若表现为「无问题」，其影响大于直接返回失败。
 *
 * <p>**发出的请求与改动前逐字相同**，此点为刻意保持：提示词与评测基线已运行多轮，
 * 不应因循环实现变更而整体漂移：
 * <ul>
 *   <li>消息顺序仍为「system 一条 + user 一条」；</li>
 *   <li>结构化输出的格式说明由 {@link BeanOutputConverter#getFormat()} 拼在最后一条 user 消息之后。
 *       此为框架 {@code .entity()} 的原有做法：{@code ChatModelCallAdvisor} 在请求上下文中
 *       取得 {@code OUTPUT_FORMAT} 后，将其 append 到最后一条 {@code UserMessage} 上。
 *       自行驱动循环时 {@code .entity()} 不再参与，因此需显式补上该步骤；</li>
 *   <li>工具定义与上下文放在 {@code options} 上，而不走 {@code .tools()} 链式方法：
 *       执行工具时需将**同一份** options 交给 {@link ToolCallingManager} 解析回调，
 *       两处必须是同一对象，否则工具调用会因「找不到该工具」而失败。
 *       {@code DefaultToolCallingManager} 是从 {@code prompt.getOptions()}
 *       中取 {@code getToolCallbacks()} 按名字匹配的。</li>
 * </ul>
 *
 * <p>工具使用 {@link ChapterReviewTools}，数据范围通过 {@code toolContext} 传递：
 * 工具为单例无状态 bean，请求级信息一律通过上下文传递。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiChapterReviewer implements ChapterReviewer {

    /** 默认最大轮数（配置缺省或取值非法时的兜底，与提示词中的「最多 3~5 次」对齐） */
    private static final int DEFAULT_MAX_TOOL_ROUNDS = 5;

    /**
     * 触发限制后追加的指令。
     *
     * <p>两项要求缺一不可：**不再要求调用工具**（否则收尾这一次仍会返回 tool_calls，多消耗一轮），
     * 以及**如实写明未查完**（模型在「查到什么算什么」的要求下容易一并写成「已核对、无矛盾」，
     * 从而给出错误的保证）。
     */
    private static final String STOP_INSTRUCTION = """
            【核对到此为止，现在给结论】
            你已经没有继续查询的机会了，不要再要求调用任何工具。
            请只根据上面已经拿到的材料与本章正文，立刻输出 JSON 结论。
            summary 里必须如实写明「跨章核对在中途被中止，这一部分没查完」，
            不许写成「已核对、没有矛盾」—— 那等于给作者一个假的保证。
            """.strip();

    /**
     * 收尾结果的标记，**由代码拼接**，不依赖模型执行。
     *
     * <p>写在 summary 最前而非作为独立字段：{@code summary} 是作者在界面上最先看到的文本，
     * 也是全文审查按章汇总时唯一展示的一条（{@code issues} 中的问题才有各自的定位）。
     */
    private static final String INCOMPLETE_MARK = "（本次跨章核对提前中止，结果不完整）";

    /**
     * 「有查询未取到材料」的标记，同样**由代码拼接**，不依赖模型自行记录。
     *
     * <p>该判定原先由一条提示词规则承担：约定模型识别以「错误」「未登录」「没有找到」「请给」开头的
     * 工具返回即为未查成。但该清单与工具实际文案分散在两个文件中，8 条失败文案中仅 1 条匹配
     * （高频的「本书没有第 N 章。」完全不匹配），规则实际未生效，模型是否如实记录无法保证。
     * 而「未查成」被写成「已核对、没有矛盾」，其影响大于直接返回失败：作者会得到错误的保证。
     *
     * <p>现改为由代码判定：每一轮工具执行完成后检查 {@link ToolResponseMessage}，
     * 用 {@link ChapterReviewTools#isNoMaterial} 逐条判定（判据为工具侧拼接的前缀，
     * 见 {@link ChapterReviewTools#NO_MATERIAL_PREFIX}），最后将次数写入 summary。
     * 记录次数而非仅写「可能不完整」，是因为作者需据此判断严重程度：1 次通常只是多问了一句，
     * 五次全部失败则说明该章的跨章核对基本未执行。
     */
    private static final String NO_MATERIAL_MARK = "（本次跨章核对有 %d 次查询未取到材料，相关结论可能不完整）";

    private final AiChatClientFactory clientFactory;

    private final ChapterReviewTools reviewTools;

    private final AiProperties props;

    @Override
    public ChapterReviewReport review(ChapterReviewRequest request) {
        ChatClient client = clientFactory.forConfig(request.baseUrl(), request.apiKey(), request.model(), false);
        BeanOutputConverter<ChapterReviewReport> converter = new BeanOutputConverter<>(ChapterReviewReport.class);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(ToolCallbacks.from(reviewTools))
                .toolContext(Map.of(
                        ChapterReviewTools.CTX_NOVEL_ID, request.novelId(),
                        ChapterReviewTools.CTX_CHAPTER_NO,
                        request.chapterNo() == null ? 0 : request.chapterNo()))
                // 关闭框架的内部循环，改由本方法自行驱动（见类注释）
                .internalToolExecutionEnabled(false)
                .build();

        List<Message> messages = new ArrayList<>(List.of(
                SystemMessage.builder().text(request.systemPrompt()).build(),
                new UserMessage(request.userPrompt() + System.lineSeparator() + converter.getFormat())));

        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();
        int maxRounds = maxToolRounds();
        long budgetNanos = budgetNanos();
        long startNanos = System.nanoTime();
        int toolExecutions = 0;
        // 本次审查中「未取到材料的查询」次数，最终由代码写入 summary
        int noMaterial = 0;

        for (int round = 1; ; round++) {
            ChatResponse response = call(client, messages, options);
            if (!response.hasToolCalls()) {
                ChapterReviewReport report = parse(converter, response);
                addNoMaterialMark(report, noMaterial);
                log.info("章节审查：完成 novelId={} chapterNo={} 轮数={} 工具执行={}次 未取到材料={}次 耗时={}ms",
                        request.novelId(), request.chapterNo(), round, toolExecutions, noMaterial,
                        (System.nanoTime() - startNanos) / 1_000_000);
                return report;
            }

            String reason = null;
            if (round >= maxRounds) {
                reason = "已达最大轮数 " + maxRounds;
            } else if (budgetNanos > 0 && System.nanoTime() - startNanos >= budgetNanos) {
                reason = "总时长预算 " + props.getReviewBudgetSeconds() + " 秒已用完";
            }
            if (reason != null) {
                return finishEarly(client, converter, messages, request, reason, round, toolExecutions, noMaterial);
            }

            // 将同一份 options 交给执行器：其需要据此解析「模型请求调用的工具对应哪个回调」
            int historySizeBefore = messages.size();
            ToolExecutionResult executed =
                    toolCallingManager.executeToolCalls(new Prompt(messages, options), response);
            List<Message> history = executed.conversationHistory();
            // 仅统计**本次新增的**工具响应：conversationHistory 为累积结果，
            // 从头部统计会重复计入前几轮的失败（次数偏大且随轮数递增）
            noMaterial += countNoMaterial(history.subList(Math.min(historySizeBefore, history.size()),
                    history.size()));
            messages = new ArrayList<>(history);
            toolExecutions++;
        }
    }

    /**
     * 统计这段消息中属于「未取到材料」的工具返回数量。
     *
     * <p>一次工具调用可能携带多个 tool_calls，因此需按 {@code getResponses()} 逐条判定，
     * 不能仅统计消息条数。
     */
    private int countNoMaterial(List<Message> messages) {
        int count = 0;
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                    if (ChapterReviewTools.isNoMaterial(response.responseData())) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 存在未取到材料的查询时，将次数写入 summary 最前；不存在时不做任何改动 */
    private void addNoMaterialMark(ChapterReviewReport report, int noMaterial) {
        if (noMaterial <= 0) {
            return;
        }
        String summary = StringUtils.hasText(report.getSummary()) ? report.getSummary().trim() : "";
        report.setSummary(String.format(NO_MATERIAL_MARK, noMaterial) + summary);
    }

    /** 单次模型调用。{@code options} 中带有工具定义，但框架不会执行它们（见类注释）。 */
    private ChatResponse call(ChatClient client, List<Message> messages, OpenAiChatOptions options) {
        ChatResponse response = client.prompt()
                .messages(messages)
                .options(options)
                .call()
                .chatResponse();
        if (response == null) {
            throw new IllegalStateException("模型没有返回任何内容");
        }
        return response;
    }

    /**
     * 收尾：不执行该轮要求的工具，追加一条指令后执行**最后一次不带工具**的调用。
     *
     * <p>丢弃「未执行的那一轮 tool_calls」而不补写工具结果的原因：助手消息中的
     * {@code tool_calls} 必须紧跟对应的工具结果，否则上游会按「对话结构非法」返回 400。
     * 直接从历史中移除是安全的：前面每一轮的 assistant/tool 配对均完整。
     */
    private ChapterReviewReport finishEarly(ChatClient client, BeanOutputConverter<ChapterReviewReport> converter,
                                            List<Message> messages, ChapterReviewRequest request, String reason,
                                            int round, int toolExecutions, int noMaterial) {
        log.warn("章节审查：工具调用提前收尾 novelId={} chapterNo={} 原因={} 轮数={} 工具执行={}次 未取到材料={}次",
                request.novelId(), request.chapterNo(), reason, round, toolExecutions, noMaterial);

        List<Message> stopMessages = new ArrayList<>(messages);
        stopMessages.add(new UserMessage(STOP_INSTRUCTION));

        ChatResponse response = call(client, stopMessages,
                OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build());

        ChapterReviewReport report = parse(converter, response);
        String summary = StringUtils.hasText(report.getSummary()) ? report.getSummary().trim() : "";
        // 两个标记同时成立时「提前中止」置于最前：该项更严重（可能材料未查完即停止），
        // 材料次数紧随其后，作者读到的顺序与严重程度一致
        report.setSummary(INCOMPLETE_MARK
                + (noMaterial > 0 ? String.format(NO_MATERIAL_MARK, noMaterial) : "")
                + summary);
        return report;
    }

    /**
     * 将模型返回的文本解析为结构对象。
     *
     * <p>内容为空时应**抛出异常**而非返回空结构：上层取得空结构会视为「审查通过」，
     * 而此处实际为「未审成」，两者混同是本功能中最危险的静默失败（作者会认为稿件已审）。
     */
    private ChapterReviewReport parse(BeanOutputConverter<ChapterReviewReport> converter, ChatResponse response) {
        String content = contentOf(response);
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("模型没有返回审查结果");
        }
        return converter.convert(content);
    }

    private String contentOf(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    private int maxToolRounds() {
        Integer configured = props.getReviewMaxToolRounds();
        return configured == null || configured < 1 ? DEFAULT_MAX_TOOL_ROUNDS : configured;
    }

    /** 总时长预算；{@code <=0}（含 null）表示不限时 */
    private long budgetNanos() {
        Integer seconds = props.getReviewBudgetSeconds();
        return seconds == null || seconds <= 0 ? 0L : TimeUnit.SECONDS.toNanos(seconds);
    }
}
