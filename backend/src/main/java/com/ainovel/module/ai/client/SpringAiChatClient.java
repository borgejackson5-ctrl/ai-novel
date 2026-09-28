package com.ainovel.module.ai.client;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.AiScene;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.exception.StreamCancelledException;
import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.module.ai.config.AiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Spring AI 实现（1.1.8）：使用 {@code ChatClient} 替代手写 {@code RestClient} 拼装协议。
 *
 * <p>与 {@link AiClient} 的分工：{@code app.ai-client=spring-ai}（默认）时生效，置为
 * {@code handwritten} 可整体切回手写实现。该开关在迁移期作为回退路径，
 * 同时使两套实现可被同一套断言对照（见 {@code AiChatClientContract}）。
 *
 * <p>「按生效配置构建客户端」由 {@link AiChatClientFactory} 承担：BYOK 下每个用户的
 * 地址/Key/模型均可能不同，而 Spring AI 的 base-url 与 api-key 属于构建期属性、不能在请求级覆盖，
 * 因此只能按配置缓存实例。该组件同时也是章节审查（工具调用 + 结构化输出）的取用入口，
 * 缓存与 Key 指纹只实现一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai-client", havingValue = "spring-ai", matchIfMissing = true)
public class SpringAiChatClient implements AiChatClient {

    private final AiProperties props;

    private final AiChatClientFactory clientFactory;

    /** 业务指标（调用量 / 耗时）：与手写实现使用同一套，两个实现切换时指标不中断 */
    private final BusinessMetrics businessMetrics;

    /** 启动时记录「当前生效的实现」：切换配置后可通过日志确认 */
    @PostConstruct
    void logImplementation() {
        log.info("AI 客户端实现：Spring AI ChatClient（app.ai-client=spring-ai）；"
                + "置为 handwritten 可切回手写 RestClient 版");
    }

    @Override
    public String chat(AiScene scene, String baseUrl, String apiKey, String model, double temperature,
                       String systemPrompt, String userPrompt) {
        return doChat(scene, false, baseUrl, apiKey, model, temperature, systemPrompt, userPrompt);
    }

    @Override
    public String chatFast(AiScene scene, String baseUrl, String apiKey, String model, double temperature,
                           String systemPrompt, String userPrompt) {
        return doChat(scene, true, baseUrl, apiKey, model, temperature, systemPrompt, userPrompt);
    }

    private String doChat(AiScene scene, boolean fast, String baseUrl, String apiKey, String model,
                          double temperature, String systemPrompt, String userPrompt) {
        requireKey(apiKey);
        long start = System.currentTimeMillis();
        try {
            // 取 ChatResponse 而非 content()：用量只在响应元数据中，content() 会将其丢弃；
            // 另发一次请求仅用于取用量会重复计费
            ChatResponse response = clientFactory.forConfig(baseUrl, apiKey, model, fast).prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(clientFactory.options(model, temperature))
                    .call()
                    .chatResponse();
            String text = contentOf(response);
            recordUsage(scene, model, usageOf(response));
            logCall(scene, model, systemPrompt.length() + userPrompt.length(), text.length(), start);
            return text;
        } catch (Exception e) {
            // 失败计数与成功计数分列，管理端据此算成功率
            businessMetrics.aiCallFailed(scene, model, "other");
            throw toBusinessException(e);
        }
    }

    @Override
    public void chatStream(AiScene scene, String baseUrl, String apiKey, String model, double temperature,
                           String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        requireKey(apiKey);
        long start = System.currentTimeMillis();
        AtomicInteger replyChars = new AtomicInteger();
        // 用量只在最后一个分片回传（请求体的 stream_options.include_usage）：逐片覆盖，结束时即为最终值
        AtomicReference<Usage> usage = new AtomicReference<>();
        try {
            clientFactory.forConfig(baseUrl, apiKey, model, false).prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(clientFactory.options(model, temperature))
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        Usage latest = usageOf(response);
                        if (latest != null) {
                            usage.set(latest);
                        }
                        String chunk = contentOf(response);
                        // 仅携带用量的收尾分片没有 choices：跳过它，否则会向 SSE 推一帧空内容
                        if (chunk.isEmpty()) {
                            return;
                        }
                        replyChars.addAndGet(chunk.length());
                        // 此处抛出的异常会被 Reactor 视为错误信号：**上游订阅立即被取消**，
                        // blockLast 随即返回。这是「用户点击停止后本端立即停止」的实现方式。
                        //
                        // 不应改为「自行 subscribe 取得 Disposable 再 dispose()」：该方式更慢
                        // （同一场景释放线程需 1439ms，而此处为 118ms），因为 Reactor 本身会处理
                        // 从 onNext 抛出的异常。（见 SpringAiChatClientTest）
                        onChunk.accept(chunk);
                    })
                    // 同步读完整条流：本方法需向调用方提供「读完才返回」的语义，
                    // 上层（AiController）在专用线程池中执行，不占用 Servlet 线程
                    .blockLast();
            recordUsage(scene, model, usage.get());
            logCall(scene, model, systemPrompt.length() + userPrompt.length(),
                    replyChars.get(), start);
        } catch (StreamCancelledException e) {
            // 用户主动停止：原样抛出。**不得进入下方的 toBusinessException**：
            // 一旦包装即变为「生成失败」，调用方会退回一次不应退回的额度。
            // 此处同样不计入失败数：主动停止不是故障，且其数量已由 sse.stream 指标记录
            throw e;
        } catch (Exception e) {
            businessMetrics.aiCallFailed(scene, model, "other");
            throw toBusinessException(e);
        }
    }

    private void requireKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "未配置 AI API Key");
        }
    }

    /**
     * 取正文。启用用量回传后，最后一个分片只带 usage、不带 choices
     * （{@code getResult()} 为 null），此时返回空串，由调用方跳过。
     */
    private static String contentOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /** 取用量元数据；上游未返回任何用量时为 null */
    private static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        return response.getMetadata().getUsage();
    }

    /**
     * 记录用量。上游未返回的字段以 0 传入，埋点层据此不写记录
     * （见 {@link BusinessMetrics#aiTokens}），避免与「确实为 0」混淆。
     */
    private void recordUsage(AiScene scene, String model, Usage usage) {
        if (usage == null) {
            return;
        }
        businessMetrics.aiTokens(scene, model,
                tokens(usage.getPromptTokens()), tokens(usage.getCompletionTokens()));
    }

    private static int tokens(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 将库抛出的异常收敛为业务异常（与手写实现同口径）：接口层只识别 {@code BusinessException}，
     * 不应将 {@code WebClientResponseException} 这类三方类型暴露给用户。
     */
    private BusinessException toBusinessException(Throwable e) {
        if (e instanceof BusinessException be) {
            return be;
        }
        log.error("调用 AI 接口失败（Spring AI）", e);
        return new BusinessException(ErrorCode.AI_GENERATE_FAIL, "调用 AI 接口失败，请检查 Key 与网络", e);
    }

    /**
     * 调用日志：只记模型、耗时与字数，**不记 Key、不记正文**。
     * 正文既可能很长（污染日志），也可能属于用户未发布的创作内容（不应写入日志）。
     */
    private void logCall(AiScene scene, String model, int promptChars, int replyChars, long startMs) {
        long costMs = System.currentTimeMillis() - startMs;
        // 与手写版一致：指标不受 enable-log 开关影响（见 AiClient 同名方法的说明）
        businessMetrics.aiCall(scene, model, costMs);
        if (!Boolean.TRUE.equals(props.getEnableLog())) {
            return;
        }
        log.info("AI 调用(spring-ai) scene={} model={} 耗时={}ms 输入={}字 输出={}字",
                scene.code(), model, costMs, promptChars, replyChars);
    }
}
