package com.ainovel.module.ai.client;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.AiScene;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.exception.StreamCancelledException;
import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.module.ai.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 客户端：手写 {@code RestClient} 直接调用 OpenAI 兼容接口（DeepSeek / Qwen / GPT 等）。
 *
 * <p>该实现作为 {@link SpringAiChatClient} 的对照基准：相同行为在框架实现中的写法、
 * 框架代为处理的部分（流式响应体、状态码判定等）均可通过两者对比得出。
 *
 * <p>默认不生效（{@code app.ai-client=spring-ai}）；将其置为 {@code handwritten} 可整体切回本实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ai-client", havingValue = "handwritten")
public class AiClient implements AiChatClient {

    private final AiProperties props;

    /** 业务指标（调用量 / 耗时）。埋点失败不影响业务，见 BusinessMetrics */
    private final BusinessMetrics businessMetrics;

    /** 全应用共用的 HTTP 客户端（见 {@code AiHttpClientConfig}），与 Spring AI 版同一份 */
    private final HttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * RestClient 线程安全，作为单例在应用生命周期内复用，
     * 避免每次调用重建连接器（DNS/连接池/超时配置均会浪费）。
     *
     * <p>两个实例按场景区分超时：内容生成可等待 60s；搜索意图解析属于交互路径，
     * 使用短超时快速失败，由调用方降级。
     */
    private RestClient restClient;

    private RestClient fastClient;

    @PostConstruct
    void init() {
        log.info("AI 客户端实现：手写 RestClient（app.ai-client=handwritten）");
        this.restClient = RestClient.builder()
                .requestFactory(clientRequestFactory(props.getTimeoutSeconds()))
                .build();
        this.fastClient = RestClient.builder()
                .requestFactory(clientRequestFactory(props.getSearchTimeoutSeconds()))
                .build();
    }

    /**
     * 单轮对话，返回模型文本输出
     */
    @Override
    public String chat(AiScene scene, String baseUrl, String apiKey, String model, double temperature,
                       String systemPrompt, String userPrompt) {
        return doChat(scene, restClient, baseUrl, apiKey, model, temperature, systemPrompt, userPrompt);
    }

    /**
     * 短超时单轮对话：用于搜索意图解析等交互路径，超时快速失败由调用方降级
     */
    @Override
    public String chatFast(AiScene scene, String baseUrl, String apiKey, String model, double temperature,
                           String systemPrompt, String userPrompt) {
        return doChat(scene, fastClient, baseUrl, apiKey, model, temperature, systemPrompt, userPrompt);
    }

    private String doChat(AiScene scene, RestClient client, String baseUrl, String apiKey, String model,
                          double temperature, String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "未配置 AI API Key");
        }

        // max_tokens 必须携带：不设置该项时，模型输出长度完全由模型决定，
        // 一次输出失控即产生实际费用（审查类功能会连续调用几百次）
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", temperature,
                "stream", false,
                "max_tokens", props.getMaxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        long start = System.currentTimeMillis();
        try {
            String response = client.post()
                    .uri(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            recordUsage(scene, model, root.path("usage"));
            logCall(scene, model, systemPrompt.length() + userPrompt.length(), content.length(), start);
            return content;
        } catch (BusinessException e) {
            businessMetrics.aiCallFailed(scene, model, "other");
            throw e;
        } catch (Exception e) {
            businessMetrics.aiCallFailed(scene, model, "other");
            log.error("调用 AI 接口失败", e);
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "调用 AI 接口失败，请检查 Key 与网络", e);
        }
    }

    /**
     * 调用日志：只记模型、耗时与字数，**不记 Key、不记正文**。
     * 正文既可能很长（污染日志），也可能属于用户未发布的创作内容（不应写入日志）。
     */
    private void logCall(AiScene scene, String model, int promptChars, int replyChars, long startMs) {
        long costMs = System.currentTimeMillis() - startMs;
        // 指标不受 enable-log 开关影响：日志供人工查看、线上可能被关闭，
        // 而「调用量 / 耗时」需长期观察趋势，不应随日志一并中断
        businessMetrics.aiCall(scene, model, costMs);
        if (!Boolean.TRUE.equals(props.getEnableLog())) {
            return;
        }
        log.info("AI 调用 scene={} model={} 耗时={}ms 输入={}字 输出={}字",
                scene.code(), model, costMs, promptChars, replyChars);
    }

    /**
     * 流式对话：调用 OpenAI 兼容接口的 stream 模式，逐 chunk 回调增量文本。
     *
     * <p>响应为 SSE 格式（每行 data: {...}），增量文本位于 choices[0].delta.content。
     *
     * <p>**必须使用 {@code exchange()} 获取原始响应体**，不能使用 {@code .retrieve().body(InputStream.class)}：
     * RestClient 默认的消息转换器**不支持将响应读取为 InputStream**，无论服务端返回
     * {@code application/json} 还是 {@code text/event-stream}，均会抛出
     * {@code no suitable HttpMessageConverter found for response type [class java.io.InputStream]}，
     * 即打字机效果会全部失败。
     *
     * @param onChunk 每收到一段增量文本时的回调
     */
    @Override
    public void chatStream(AiScene scene, String baseUrl, String apiKey, String model, double temperature,
                           String systemPrompt, String userPrompt, Consumer<String> onChunk) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "未配置 AI API Key");
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", temperature,
                "stream", true,
                // 流式响应默认不回传用量，需显式要求：不打开则本项目消耗最大的生成类场景
                // 在管理端统计里整段缺失（见 SpringAiChatClient 同名选项）
                "stream_options", Map.of("include_usage", true),
                "max_tokens", props.getMaxTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        long start = System.currentTimeMillis();
        SseResult result;
        try {
            // exchange 会绕过消息转换器与状态码异常机制，因此 HTTP 错误状态需自行判断。
            // 在 lambda 内读完流，响应关闭交由 Spring 处理（不要将其返回到 lambda 外部）。
            result = restClient.post()
                    .uri(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status < 200 || status >= 300) {
                            String errBody = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            log.error("AI 流式接口返回非 2xx: status={} body={}", status, errBody);
                            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL,
                                    "调用 AI 接口失败，请检查 Key 与网络");
                        }
                        return readSse(response.getBody(), onChunk);
                    });
            recordUsage(scene, model, result);
            logCall(scene, model, systemPrompt.length() + userPrompt.length(), result.chars(), start);
        } catch (StreamCancelledException e) {
            // 主动停止不属于失败：原样抛出，不包装为业务异常（见 AiChatClient 上的契约说明），
            // 也不计入失败数。该抛出同时会关闭 readSse 中 try-with-resources 持有的输入流，
            // 连接中断后模型侧才会真正停止；若吞掉该异常，用户已关闭页面，服务端仍会继续生成完毕
            throw e;
        } catch (BusinessException e) {
            // try 块内抛出业务异常的唯一来源是「HTTP 非 2xx」，原样抛出以保留原有提示文案
            businessMetrics.aiCallFailed(scene, model, "other");
            throw e;
        } catch (Exception e) {
            businessMetrics.aiCallFailed(scene, model, "other");
            log.error("调用 AI 流式接口失败", e);
            throw new BusinessException(ErrorCode.AI_GENERATE_FAIL, "调用 AI 流式接口失败", e);
        }
    }

    /**
     * 读 SSE 流并逐段回调，返回累计字符数（只用于日志，不记正文）与上游回传的用量。
     *
     * <p>启用用量回传后，收尾分片只带 {@code usage}、{@code choices} 为空数组：
     * 该分片不产生回调，因此正文与用量需分开判断。
     */
    private SseResult readSse(InputStream is, Consumer<String> onChunk) throws IOException {
        int chars = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        try (InputStream in = is;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                JsonNode node = objectMapper.readTree(data);
                String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                    chars += delta.length();
                    onChunk.accept(delta);
                }
                JsonNode usage = node.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    // 以最后一次非零值为准，而不是累加：按规范用量只在收尾分片回传一次；
                    // 若上游逐片回传（非规范实现），累加会成倍放大
                    int prompt = usage.path("prompt_tokens").asInt(0);
                    int completion = usage.path("completion_tokens").asInt(0);
                    if (prompt > 0) {
                        promptTokens = prompt;
                    }
                    if (completion > 0) {
                        completionTokens = completion;
                    }
                }
            }
        }
        return new SseResult(chars, promptTokens, completionTokens);
    }

    /** 一次流式读取的结果：正文累计字符数（仅用于日志）与上游回传的用量 */
    private record SseResult(int chars, int promptTokens, int completionTokens) {
    }

    /**
     * 记录非流式响应的用量。OpenAI 兼容接口在非流式响应中返回 {@code usage}；
     * 少数服务不返回该字段，此时取到 0，埋点层据此不写记录（见 {@link BusinessMetrics#aiTokens}）。
     */
    private void recordUsage(AiScene scene, String model, JsonNode usage) {
        businessMetrics.aiTokens(scene, model,
                usage.path("prompt_tokens").asInt(0), usage.path("completion_tokens").asInt(0));
    }

    /** 记录流式响应的用量 */
    private void recordUsage(AiScene scene, String model, SseResult result) {
        businessMetrics.aiTokens(scene, model, result.promptTokens(), result.completionTokens());
    }

    private org.springframework.http.client.ClientHttpRequestFactory clientRequestFactory(int timeoutSeconds) {
        org.springframework.http.client.JdkClientHttpRequestFactory factory =
                new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return factory;
    }
}
