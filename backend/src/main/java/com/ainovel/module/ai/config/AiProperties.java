package com.ainovel.module.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 配置（OpenAI 兼容接口）。
 *
 * <p>**字段名必须与 application.yaml 中的 key 一一对应**：不一致时 Spring 会**静默忽略**
 * 该 key，既不报错也不提示，等同于未配置。修改此处须同步修改 yaml，反之亦然，
 * {@code AiPropertiesBindingTest} 是这一约定的守门测试。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /** 接口地址（默认 DeepSeek） */
    private String baseUrl = "https://api.deepseek.com/v1";

    /** API Key，留空时降级 mock */
    private String apiKey = "";

    private String model = "deepseek-chat";

    private Double temperature = 0.8;

    /** 内容生成超时（秒） */
    private Integer timeoutSeconds = 60;

    /** 智能搜索意图解析超时（秒）：交互路径 fail-fast，超时立即降级关键词搜索 */
    private Integer searchTimeoutSeconds = 5;

    /**
     * 建连（TCP 握手）超时（秒）。
     *
     * <p>与 {@link #timeoutSeconds} 含义不同：后者为「请求发出后等待响应」的时限，
     * Spring 会将其映射为 {@code HttpRequest.timeout}（该时限覆盖整个请求、含建连阶段）；
     * 本项为握手本身的上限。两者均设置是为了让**不可达的地址在数秒内失败**，
     * 而不将标准档的 60 秒消耗在建连上（JDK 默认不设连接超时，等同于交由操作系统兜底，
     * Windows 约 21 秒、Linux 约 130 秒）。
     */
    private Integer connectTimeoutSeconds = 5;

    /**
     * 单次回复的最大 token 数。
     *
     * <p>必须**实际写入请求体**（见 {@code AiClient}），否则模型输出长度完全不受本项目控制，
     * 单次回复失控将导致账单不可控。
     */
    private Integer maxTokens = 2000;

    /** 是否记录调用日志（仅记模型 / 耗时 / 字数，不记 Key 与正文） */
    private Boolean enableLog = true;

    private Boolean mockEnabled = true;

    /**
     * 单章审查时，模型**最多可以要求调用几轮工具**。
     *
     * <p>默认 5，与提示词中「一章查 2 次左右，最多 3~5 次」对齐：提示词正常生效时
     * 该上限不会触发，仅在模型未按预期行为时兜底。
     *
     * <p>该上限为必要项：Spring AI 1.1.8 的内部工具循环**没有轮数上限**，
     * 框架会持续「执行工具 → 再问模型」直至模型不再要求工具。而单章审查是**同步 HTTP 接口**
     * （不像流式创作那样有 {@code SseEmitter} 的超时兜底），模型反复调用工具时该请求会一直占用
     * 线程，既不返回也不断开。
     *
     * <p>取值 &lt;1 时回落到 5（配置错误不应使功能失效）。
     */
    private Integer reviewMaxToolRounds = 5;

    /**
     * 单章审查的**总时长预算**（秒），默认 120。
     *
     * <p>在轮数限制之外增加一道，用于覆盖「每一轮耗时都很长」的情况：单轮已有
     * {@code timeoutSeconds} 兜底，但五轮各 60 秒合计为五分钟，对同步接口而言过长。
     *
     * <p>{@code <= 0} 表示不限时（仅按轮数限制）。
     */
    private Integer reviewBudgetSeconds = 120;
}
