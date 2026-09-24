package com.ainovel.module.ai.client;

import com.ainovel.module.ai.config.AiProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 按「生效配置」取一个可用的 {@link ChatClient}。
 *
 * <p>需要该组件而非注入单例的原因：Spring AI 的 {@code base-url} 与 {@code api-key} 是
 * {@code OpenAiApi} 的**构建期**属性，运行期只能覆盖 model / temperature / maxTokens 这类模型参数
 * （官方文档明确：api-key 与 base-url 不能在请求级覆盖）。而本站支持 BYOK，
 * 每个用户可能使用不同的服务商、地址与模型，因此只能按「地址 + 模型 + Key 指纹 + 超时档」
 * 分别构建实例并缓存。
 *
 * <p>抽成独立组件是为了让**所有使用方共用同一份实现**：{@link SpringAiChatClient}
 * 的普通对话、章节审查的工具调用与结构化输出均由此处取客户端，
 * 缓存与 Key 指纹只实现一次，新功能不会绕开它自行创建实例。
 *
 * <p>两处与手写实现刻意对齐：{@code completionsPath} 显式写为 {@code /chat/completions}
 * （本项目 baseUrl 约定包含版本段，使用库默认值会拼成 {@code /v1/v1/...}）；关闭重试
 * （{@code maxAttempts(1)}，Spring AI 默认为 10 次，审查类功能连续调用几百次，一次抖动即产生
 * 10 倍账单）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiChatClientFactory {

    /** 统一补在 baseUrl 后面的资源路径（baseUrl 约定已含版本段） */
    private static final String COMPLETIONS_PATH = "/chat/completions";

    /** 构建期的默认温度；实际每次调用的温度由请求级 options 覆盖 */
    private static final double DEFAULT_TEMPERATURE = 0.8;

    private final AiProperties props;

    /**
     * 全应用共用的 HTTP 客户端（见 {@code AiHttpClientConfig}）。
     *
     * <p>**不在此处创建**：本类会按「地址 + 模型 + Key 指纹」缓存最多 200 个客户端，
     * 若各自持有 HttpClient 即对应 200 套 selector 线程与连接池，
     * 线程数会随「配置了自有 Key 的用户数」上涨，与业务量无关。
     */
    private final HttpClient httpClient;

    /**
     * 已构建的客户端缓存：键为「地址|模型|Key指纹|超时档」。
     *
     * <p>上限与过期时间均为必要项：每个访问者都可能携带自己的配置，不设上限会造成内存泄漏。
     * Key 仅以**指纹**参与键名，原始 Key 不落在任何可读位置。
     */
    private final Cache<String, ChatClient> clients = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /** 不重试（见类注释）。Spring AI 默认的 RetryTemplate 会重试 10 次。 */
    private final RetryTemplate noRetry = RetryTemplate.builder().maxAttempts(1).build();

    /**
     * 取该配置对应的客户端（同一份配置复用同一个实例）。
     *
     * @param fast true 表示使用搜索的短超时档（交互路径需快速失败）
     */
    public ChatClient forConfig(String baseUrl, String apiKey, String model, boolean fast) {
        String key = baseUrl + '|' + model + '|' + apiKeyFingerprint(apiKey) + '|' + (fast ? "fast" : "std");
        return clients.get(key, ignored -> build(baseUrl, apiKey, model, fast));
    }

    /**
     * 请求级选项：模型参数（model / temperature / maxTokens）。
     *
     * <p>这些参数**可以**在请求级覆盖，与 baseUrl/apiKey 不同。
     * 抽成方法是为了让「闲聊」与「审查」两条路径的取值口径一致（尤其 maxTokens 必须发出）。
     */
    public OpenAiChatOptions options(String model, double temperature) {
        return OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(props.getMaxTokens())
                .build();
    }

    private ChatClient build(String baseUrl, String apiKey, String model, boolean fast) {
        // 超时沿用项目的两档：内容生成可等待，搜索/审查这类需快速失败
        int timeoutSeconds = fast ? props.getSearchTimeoutSeconds() : props.getTimeoutSeconds();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath(COMPLETIONS_PATH)
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options(model, DEFAULT_TEMPERATURE))
                .retryTemplate(noRetry)
                .build();

        return ChatClient.create(chatModel);
    }

    /** Key 指纹：只用于缓存键，避免原始 Key 出现在任何键名或日志里 */
    private String apiKeyFingerprint(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(Arrays.copyOf(digest, 8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 为 JDK 必备算法，正常不会进入该分支；若确实进入，也不应使一次对话直接失败
            return Integer.toHexString(apiKey.hashCode());
        }
    }
}
