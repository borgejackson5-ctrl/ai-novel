package com.ainovel.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假的「OpenAI 兼容」服务端，在集成测试中替代真实模型。
 *
 * <p>引入的原因：生成与审查是项目中链路最长的两条，而集成测试此前一直绕开它们：
 * 调用真实模型既产生费用又依赖网络，且无法稳定复现失败，因此「AI 这一段的集成测试」长期空白，
 * 而这段恰是问题最集中的部分（流式收尾、扣费与退款、失败文案）。
 *
 * <p>该桩只做一件事：按 OpenAI 的格式应答。它不是 mock：应用确实通过 HTTP 请求它，
 * 真实的 RestClient、真实的 SSE 解析、真实的超时与异常包装都会执行，
 * 只是对端换成了由测试控制的进程。因此它能验证的内容，{@code @MockBean} 无法验证。
 *
 * <p>三个能力开关用于将异常路径也变成可复现的用例：
 * <ul>
 *   <li>{@link #failWith(int)}：让上游返回指定错误码，用于验证失败文案与额度退还；</li>
 *   <li>{@link #chunksPerStream(int)}：控制流的段数，使客户端可在中途断开（取消用例需要）；</li>
 *   <li>{@link #callCount()}：调用次数，用于证明确实请求了模型，而非走了本地降级。</li>
 * </ul>
 */
public final class FakeOpenAiServer {

    /** 与 AiChatClientFactory 里拼路径的口径一致：baseUrl 已含版本段，这里只补资源路径 */
    private static final String COMPLETIONS_PATH = "/v1/chat/completions";

    private static final String MODEL = "stub-model";

    /** 默认输出 8 段：每段之间保留间隔，客户端才有机会在中途断开 */
    private static final int DEFAULT_CHUNKS = 8;

    private static final long CHUNK_GAP_MS = 60L;

    /** 流式收尾分片回传的用量，取固定值供集成测试断言 */
    public static final int STREAM_PROMPT_TOKENS = 12;

    public static final int STREAM_COMPLETION_TOKENS = 34;

    /** 非流式响应回传的用量 */
    public static final int SYNC_PROMPT_TOKENS = 1;

    public static final int SYNC_COMPLETION_TOKENS = 1;

    private final HttpServer server;

    private final AtomicInteger calls = new AtomicInteger();

    private final AtomicInteger chunksPerStream = new AtomicInteger(DEFAULT_CHUNKS);

    /** 0 表示正常响应；其它值表示让上游返回该状态码 */
    private volatile int failWithStatus = 0;

    private FakeOpenAiServer(HttpServer server) {
        this.server = server;
    }

    public static FakeOpenAiServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeOpenAiServer stub = new FakeOpenAiServer(server);
            server.createContext(COMPLETIONS_PATH, stub::handle);
            // 守护线程池：避免测试 JVM 退出时因本桩仍持有线程而无法结束
            server.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "fake-openai");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("假模型服务起不来：" + e.getMessage(), e);
        }
    }

    /** 写入 t_ai_config.base_url 的值（后续会拼上 /chat/completions） */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    public String model() {
        return MODEL;
    }

    /** 让上游返回指定状态码（0 表示恢复正常） */
    public void failWith(int status) {
        this.failWithStatus = status;
    }

    /** 设置单次流式响应的段数 */
    public void chunksPerStream(int n) {
        chunksPerStream.set(n);
    }

    public int callCount() {
        return calls.get();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        int fail = failWithStatus;
        if (fail != 0) {
            byte[] body = ("{\"error\":{\"message\":\"假模型故意失败\",\"type\":\"server_error\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }

        // 应用发送的是 OpenAI 请求体，stream 字段决定是否走 SSE
        String compact = request.replace(" ", "");
        if (compact.contains("\"stream\":true")) {
            // 真实上游只在请求体要求时才回传用量。此处照此实现：若应用忘了带
            // stream_options.include_usage，集成测试就取不到用量而失败 —— 这是唯一能
            // 证伪「流式 token 统计是否真的接通」的方式
            respondStream(exchange, compact.contains("\"include_usage\":true"));
        } else {
            respondOnce(exchange);
        }
    }

    private void respondOnce(HttpExchange exchange) throws IOException {
        String json = """
                {"id":"chatcmpl-stub","object":"chat.completion","created":1,"model":"%s",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}
                """.formatted(MODEL, content(1), SYNC_PROMPT_TOKENS, SYNC_COMPLETION_TOKENS,
                SYNC_PROMPT_TOKENS + SYNC_COMPLETION_TOKENS);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void respondStream(HttpExchange exchange, boolean includeUsage) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        // 长度 0 表示分块传输，才能逐段推送
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            int chunks = chunksPerStream.get();
            for (int i = 1; i <= chunks; i++) {
                write(out, chunk(content(i), null));
                Thread.sleep(CHUNK_GAP_MS);
            }
            write(out, chunk(null, "stop"));
            if (includeUsage) {
                write(out, usageChunk());
            }
            write(out, "data: [DONE]\n\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 客户端中途断开时 out.write 抛出 IOException，方法就此结束，与真实上游行为一致
    }

    /**
     * 收尾的用量分片。
     *
     * <p>{@code choices} 为空数组，与真实上游一致 —— 解析方必须能处理「没有 choices」的分片，
     * 按 {@code choices[0]} 直接取正文会在这里崩或取到空值。
     */
    private static String usageChunk() {
        return "data: {\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\""
                + MODEL + "\",\"choices\":[],\"usage\":{\"prompt_tokens\":" + STREAM_PROMPT_TOKENS
                + ",\"completion_tokens\":" + STREAM_COMPLETION_TOKENS
                + ",\"total_tokens\":" + (STREAM_PROMPT_TOKENS + STREAM_COMPLETION_TOKENS) + "}}\n\n";
    }

    private static String content(int index) {
        return "第" + index + "段流式内容";
    }

    private static String chunk(String content, String finishReason) {
        StringBuilder sb = new StringBuilder();
        sb.append("data: {\"id\":\"chatcmpl-stub\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"")
                .append(MODEL).append("\",\"choices\":[{\"index\":0,\"delta\":{");
        if (content != null) {
            sb.append("\"role\":\"assistant\",\"content\":\"").append(escape(content)).append("\"");
        }
        sb.append("},\"finish_reason\":").append(finishReason == null ? "null" : "\"" + finishReason + "\"")
                .append("}]}\n\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
