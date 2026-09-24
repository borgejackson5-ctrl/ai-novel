package com.ainovel.module.ai.client;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.exception.StreamCancelledException;
import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.module.ai.config.AiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
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
    public String chat(String baseUrl, String apiKey, String model, double temperature,
                       String systemPrompt, String userPrompt) {
        return doChat("chat", false, baseUrl, apiKey, model, temperature, systemPrompt, userPrompt);
    }

    @Override
    public String chatFast(String baseUrl, String apiKey, String model, double temperature,
                           String systemPrompt, String userPrompt) {
        return doChat("chatFast", true, baseUrl, apiKey, model, temperature, systemPrompt, userPrompt);
    }

    private String doChat(String scene, boolean fast, String baseUrl, String apiKey, String model,
                          double temperature, String systemPrompt, String userPrompt) {
        requireKey(apiKey);
        long start = System.currentTimeMillis();
        try {
            String content = clientFactory.forConfig(baseUrl, apiKey, model, fast).prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(clientFactory.options(model, temperature))
                    .call()
                    .content();
            String text = content == null ? "" : content;
            logCall(scene, model, systemPrompt.length() + userPrompt.length(), text.length(), start);
            return text;
        } catch (Exception e) {
            throw toBusinessException(e);
        }
    }

    @Override
    public void chatStream(String baseUrl, String apiKey, String model, double temperature,
                           String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        requireKey(apiKey);
        long start = System.currentTimeMillis();
        AtomicInteger replyChars = new AtomicInteger();
        try {
            clientFactory.forConfig(baseUrl, apiKey, model, false).prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .options(clientFactory.options(model, temperature))
                    .stream()
                    .content()
                    .doOnNext(chunk -> {
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
            logCall("chatStream", model, systemPrompt.length() + userPrompt.length(),
                    replyChars.get(), start);
        } catch (StreamCancelledException e) {
            // 用户主动停止：原样抛出。**不得进入下方的 toBusinessException**：
            // 一旦包装即变为「生成失败」，调用方会退回一次不应退回的额度
            throw e;
        } catch (Exception e) {
            throw toBusinessException(e);
        }
    }

    private void requireKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "未配置 AI API Key");
        }
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
    private void logCall(String scene, String model, int promptChars, int replyChars, long startMs) {
        long costMs = System.currentTimeMillis() - startMs;
        // 与手写版一致：指标不受 enable-log 开关影响（见 AiClient 同名方法的说明）
        businessMetrics.aiCall(scene, model, costMs);
        if (!Boolean.TRUE.equals(props.getEnableLog())) {
            return;
        }
        log.info("AI 调用(spring-ai) scene={} model={} 耗时={}ms 输入={}字 输出={}字",
                scene, model, costMs, promptChars, replyChars);
    }
}
