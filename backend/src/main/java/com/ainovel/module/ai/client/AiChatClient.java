package com.ainovel.module.ai.client;

import com.ainovel.common.exception.StreamCancelledException;

import java.util.function.Consumer;

/**
 * AI 对话能力：一次问答、短超时问答、流式问答。
 *
 * <p>本项目同时存在两套实现，故抽出该接口：
 * <ul>
 *   <li>{@link AiClient}：手写 {@code RestClient} 直接拼装 OpenAI 兼容协议；</li>
 *   <li>{@link SpringAiChatClient}：基于 Spring AI 1.1.8 的 {@code ChatClient} 实现。</li>
 * </ul>
 * 抽出接口的目的不是「可能更换实现」，而是使**同一套断言可在两个实现上运行**：
 * {@code AiChatClientContract} 中的用例（请求体必须带 max_tokens、流式逐段回调、
 * 非 2xx 转业务异常、空 Key 不发起请求）对两者均成立，迁移是否等价由该契约判定，
 * 而非依据「看起来差不多」。实际生效的实现由 {@code app.ai-client} 配置决定。
 *
 * <p>方法签名中携带 {@code baseUrl}/{@code apiKey}/{@code model} 是**有意设计**：
 * 本站支持 BYOK，每个用户可能使用不同的服务商与地址，调用方每次均需传入生效配置。
 */
public interface AiChatClient {

    /**
     * 单轮对话，返回模型文本输出。
     *
     * @param baseUrl    形如 {@code https://api.deepseek.com/v1}（**含**版本段，客户端只补 {@code /chat/completions}）
     * @param temperature 采样温度
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入
     */
    String chat(String baseUrl, String apiKey, String model, double temperature,
                String systemPrompt, String userPrompt);

    /**
     * 短超时单轮对话：用于搜索意图解析这类交互路径，超时快速失败、由调用方降级。
     */
    String chatFast(String baseUrl, String apiKey, String model, double temperature,
                    String systemPrompt, String userPrompt);

    /**
     * 流式对话，逐段回调增量文本（打字机效果）。
     *
     * <p>该方法为同步方法：内部读完整个流后才返回，因此调用方需在自有线程池中执行（见 {@code AiController}）。
     * 用户中途停止时，该路径需**尽快返回**以释放线程（手写实现 71ms、Spring AI 实现 118ms；
     * 改为「自行 subscribe 再 dispose」为 1439ms，不应采用）。
     *
     * <p>**{@code onChunk} 抛出 {@link StreamCancelledException} 时的契约（两个实现均须遵守）**：
     * <ol>
     *   <li>**立即停止**，不再回调后续内容；</li>
     *   <li>将该异常**原样向上抛出**，不得包装为其他异常：它是「用户主动停止」的信号，
     *       若被当作失败处理，调用方会多退回一次额度；</li>
     *   <li>**尽力取消上游请求**：这是该设计的核心目的。做法是「让异常穿过读流循环」，流关闭后
     *       连接即中断，模型侧才会真正停止。</li>
     * </ol>
     *
     * <p>**第 3 条目前仅手写实现可以做到**：{@link SpringAiChatClient} 的流只识别 EOF，
     * 取消无法传入其读取循环（四种写法均已尝试，验证记录见 {@code SpringAiChatClientTest}）。
     * 因此该契约表述为「尽力」而非「必须」，但**无法承诺的行为需明确写出**，
     * 否则阅读代码者会认为点击停止即可省下这段输出。
     *
     * @param onChunk 每收到一段增量文本时的回调
     */
    void chatStream(String baseUrl, String apiKey, String model, double temperature,
                    String systemPrompt, String userPrompt, Consumer<String> onChunk);
}
