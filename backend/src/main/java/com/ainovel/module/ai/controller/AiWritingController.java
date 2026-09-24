package com.ainovel.module.ai.controller;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.SseConstant;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.exception.StreamCancelledException;
import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.common.util.SsePayload;
import com.ainovel.module.ai.domain.PolishMode;
import com.ainovel.module.ai.domain.WritingLength;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.domain.form.AiContinueForm;
import com.ainovel.module.ai.domain.form.AiPolishForm;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.ai.service.AiWritingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * AI 写作接口：续写与润色（阶段 6）。
 *
 * <p>与 {@link AiController} 分开的原因：后者为「起名/简介」，输入较短，走 GET query 即可；
 * 本类处理作者自己的正文，几千字必须走 POST。两者的入参形态、扣费口径、可用性规则均不同，
 * 合并到一个类中须按 type 字符串分叉，而 type 传错不会报错，只会静默进入其他场景的提示词。
 *
 * <p>**顺序为硬约束**：估额（同时校验入参）→ 判断是否可写 → 扣费 → 最后创建 SSE。
 * 任何一步置于 SSE 之后，失败都会表现为 200 的空流：页面持续加载、日志无异常、用户无从判断。
 */
@Slf4j
@Tag(name = "AI 写作")
@RestController
@RequestMapping("/ai/write")
public class AiWritingController {

    /**
     * SSE 连接超时。**必须赋值**：0 表示永不超时，阻塞的流会一直占用线程。
     *
     * <p>原先两处各自硬编码同一常量，与扣费响应头、前端帧解析属于同类问题（同一语义存在两份，
     * 修改一处遗漏另一处），因此收敛为一个配置项；另一作用是**测试超时路径时可将其调整为几毫秒**，
     * 否则只能等待五分钟。
     *
     * <p>写成**带初始值**的字段：单测没有 Spring 上下文，没有初始值的 {@code @Value}
     * 为 0，而 0 即「永不超时」，等同于该保护在测试中失效。
     */
    @Value("${app.sse.timeout-ms:300000}")
    private long sseTimeoutMs = 300_000L;

    private final AiWritingService aiWritingService;

    private final AiConfigService aiConfigService;

    private final ObjectMapper objectMapper;

    /** SSE 专用线程池，避免阻塞 Servlet 线程。按名称注入：Executor 类型的 Bean 不止一个 */
    private final Executor sseExecutor;

    /** 流式收尾结果埋点：开流即返回 200，成功与失败在 HTTP 层无法区分，需单独记录 */
    private final BusinessMetrics businessMetrics;

    /** 手写构造器：Lombok 不会将 @Qualifier 带到构造器参数上（全项目仅此一处需要） */
    public AiWritingController(AiWritingService aiWritingService, AiConfigService aiConfigService,
                               ObjectMapper objectMapper,
                               BusinessMetrics businessMetrics,
                               @Qualifier("sseExecutor") Executor sseExecutor) {
        this.aiWritingService = aiWritingService;
        this.aiConfigService = aiConfigService;
        this.objectMapper = objectMapper;
        this.businessMetrics = businessMetrics;
        this.sseExecutor = sseExecutor;
    }

    @Operation(summary = "AI 续写（流式：读本章末尾一段作为上文，按方向与长度档位往下写）")
    @PostMapping(value = "/continue", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(name = "ai:write", limit = 20)
    public SseEmitter continueWriting(@Valid @RequestBody AiContinueForm form, HttpServletResponse response) {
        // userId 必须在此处获取：下方异步线程中没有登录上下文，无法获取
        Long userId = LoginUserUtil.getUserId();
        // 估额同时将「正文为空」这类入参问题拦截在开流之前
        int units = aiWritingService.estimateContinueUnits(form.getContent(), form.getDirection());
        WritingLength length = WritingLength.of(form.getLength());

        AiConfig config = aiConfigService.getActiveConfigForUser(userId, units);
        aiWritingService.requireContinueReady(config);

        return stream("continue", userId, config, units, response,
                onChunk -> aiWritingService.continueWriting(
                        form.getContent(), form.getDirection(), length, config, onChunk));
    }

    @Operation(summary = "AI 润色（流式：对选中的一段做「改通顺 / 精简 / 加画面感」）")
    @PostMapping(value = "/polish", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(name = "ai:write", limit = 20)
    public SseEmitter polish(@Valid @RequestBody AiPolishForm form, HttpServletResponse response) {
        Long userId = LoginUserUtil.getUserId();
        // 改法无法解析时立即拒绝：润色会直接修改作者已有文字，若选择「精简」却按「加画面感」执行，
        // 作者会得到一段更长的文字，从而认为功能异常
        PolishMode mode = PolishMode.of(form.getMode());
        if (mode == null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请选择要改的方式");
        }
        int units = aiWritingService.estimatePolishUnits(form.getContent());

        AiConfig config = aiConfigService.getActiveConfigForUser(userId, units);
        aiWritingService.requirePolishReady(config);

        return stream("polish", userId, config, units, response,
                onChunk -> aiWritingService.polish(form.getContent(), mode, config, onChunk));
    }

    /**
     * 将「已扣费的配置 + 逐段回调的生成动作」封装为 SSE。
     *
     * <p>两点需注意：
     * <ol>
     *   <li>失败需退回额度，且退**原数**（扣多少退多少）：作者不应为一次未完成的生成付费，
     *       退成「1」会导致额度计算错误；</li>
     *   <li>每段内容需先编码为 JSON 字符串再发送。SSE 的一帧以空行分隔，帧内 {@code data:}
     *       之后不能出现真实换行：续写输出天然带段落换行，直接写入会把一帧拆为两帧，
     *       后半部分被当作未知字段丢弃。表现为「生成的文字缺少若干换行」，不报错，仅排版静默异常。</li>
     * </ol>
     *
     * @param api 入口名（continue / polish），仅用于收尾结果埋点：两个入口共用该段逻辑，
     *            不带上名称则无法在指标上区分是续写还是润色出现问题
     */
    private SseEmitter stream(String api, Long userId, AiConfig config, int units,
                              HttpServletResponse response, Consumer<Consumer<String>> producer) {
        // 响应尚未提交（首次 send 时才提交），此时设置响应头有效
        response.setHeader(SseConstant.HEADER_CHARGED_UNITS, String.valueOf(units));
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);

        CompletableFuture.runAsync(() -> {
            try {
                producer.accept(chunk -> {
                    try {
                        emitter.send(SseEmitter.event().data(SsePayload.encode(objectMapper, chunk)));
                    } catch (IOException e) {
                        // 客户端已断开（关闭页面/点击停止）。**此处必须抛出异常**，不能仅记录日志后返回：
                        // 一旦返回，上层会继续把整段生成完毕，额外消耗输出 token 并继续占用该线程，
                        // 而用户端早已断开。抛出之后：读流循环被打断、线程快速释放，
                        // 在支持取消上游的实现上还会一并中断模型的请求（手写实现支持；
                        // Spring AI 实现无法取消上游，原因与验证见 SpringAiChatClientTest）。
                        throw new StreamCancelledException("客户端已断开，停止生成", e);
                    } catch (IllegalStateException e) {
                        // emitter 已结束，最常见的来源是 **SSE 超时**：容器回收连接、
                        // Spring 将 emitter 标记为 completed，下一次 send 抛出
                        // 「ResponseBodyEmitter has already completed」。
                        // 关键是使其进入**同一条** StreamCancelledException 通道：否则
                        // 会被包装为「调用 AI 接口失败，请检查 Key 与网络」，
                        // 该 ERROR 与整页堆栈会将排查方向引向错误处（曾出现超时却排查 Key 的情况）。
                        // 不依据 message 文本判断（文本会随国际化变化），仅依据异常类型。
                        throw new StreamCancelledException("流已结束（超时），停止生成", e, true);
                    }
                });
                emitter.complete();
                businessMetrics.sseStream(api, "done");
            } catch (StreamCancelledException e) {
                // 是否退回额度取决于原因（该语义由异常携带）：
                //   主动停止 → **不退回**（界面已注明「额度不会退回」，且成本确已产生）；
                //   流超时 → **退回**（用户等待五分钟未取得任何内容，按「失败退回」处理）
                if (e.isRefundable()) {
                    businessMetrics.sseStream(api, "timeout");
                    aiConfigService.refundQuotaIfCharged(config, userId);
                    log.warn("生成中断，已归还本次额度：{}", e.getMessage());
                } else {
                    businessMetrics.sseStream(api, "cancelled");
                    log.info("本次生成已停止：{}", e.getMessage());
                }
                completeQuietly(emitter);
            } catch (Exception e) {
                businessMetrics.sseStream(api, "error");
                aiConfigService.refundQuotaIfCharged(config, userId);
                log.error("AI 写作失败，已归还本次额度", e);
                emitter.completeWithError(e);
            }
        }, sseExecutor);
        return emitter;
    }

    /** 客户端已断开，收尾失败无影响（再次抛出只会填满日志） */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开，无可用操作
        }
    }
}
