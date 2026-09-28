package com.ainovel.module.ai.client;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.AiScene;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.exception.StreamCancelledException;
import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.module.ai.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 客户端的契约测试：同一套断言运行在两个实现上。
 *
 * <p>这样组织的原因：手写实现（{@link AiClient}）与 Spring AI 实现
 * （{@link SpringAiChatClient}）是同一功能的两套写法，迁移是否等价不应由「看起来差不多」判断，
 * 而应由同一组断言判定：请求体中是否含 {@code max_tokens}、流式是否逐段回调、
 * 非 2xx 是否转换为业务异常、空 Key 是否会真正发出请求。
 *
 * <p>使用 JDK 自带的 HttpServer 作为假模型服务，断言真实发出的请求体：
 * 这类字段一旦漏发，代码不报错、单测也不失败，只有账单能反映出来
 * （审查类功能会连续调用数百次）。
 *
 * <p>两个实现若存在行为差异，会在此处以「某个子类不通过」的形式暴露；若差异是有意保留的
 * （例如实现中省略了默认值），在该子类中覆盖对应用例并说明原因。
 */
abstract class AiChatClientContract {

    /** 被测实现由子类提供（同包，不需要 public） */
    abstract AiChatClient createClient(AiProperties props);

    /**
     * 由 {@link #createClient} 构建实例时使用的指标注册表。
     *
     * <p>token 用量的断言依赖它：用量由上游返回，不能从请求体或返回值反推，
     * 只能验证「有没有上报到指标」。子类在 {@code createClient} 中赋值。
     */
    protected SimpleMeterRegistry meterRegistry;

    /** 与 {@link #meterRegistry} 配套的指标门面 */
    protected BusinessMetrics metrics;

    /** 读取某个 kind（prompt / completion）的 token 累计值 */
    protected double tokenCount(String kind) {
        Counter counter = meterRegistry.find("ainovel.ai.tokens")
                .tags("scene", "TITLE", "model", "deepseek-chat", "kind", kind)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    /** 起一个假的 OpenAI 兼容服务，返回固定响应，并把收到的请求体记下来 */
    protected HttpServer fakeOpenAi(AtomicReference<String> receivedBody, String responseBody) throws Exception {
        return fakeOpenAi(receivedBody, responseBody, "application/json");
    }

    protected HttpServer fakeOpenAi(AtomicReference<String> receivedBody, String responseBody,
                                    String contentType) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    protected AiChatClient clientFor(int maxTokens) {
        AiProperties props = new AiProperties();
        props.setMaxTokens(maxTokens);
        props.setTimeoutSeconds(5);
        props.setSearchTimeoutSeconds(5);
        return createClient(props);
    }

    protected String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /**
     * 「慢速流」假服务：每 {@code intervalMs} 推送一段，共推送 {@code total} 段。
     *
     * <p>用途是验证上游请求是否被取消：客户端一旦断开，此处再向连接写入即抛 IOException。
     * 这是「用户点击停止后，模型侧是否也停止」唯一可观测的证据：
     * 仅观察客户端不再回调，只能说明前端已停止。
     *
     * @param sent           实际写入成功的段数
     * @param upstreamBroken 写入失败时置 true（表示上游连接已断，模型侧不会继续生成）
     */
    protected HttpServer fakeSlowStream(AtomicInteger sent, AtomicBoolean upstreamBroken,
                                        int total, long intervalMs) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                for (int i = 0; i < total; i++) {
                    os.write(("data: {\"choices\":[{\"delta\":{\"content\":\"第" + i + "段\"}}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    sent.incrementAndGet();
                    Thread.sleep(intervalMs);
                }
                os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException | InterruptedException e) {
                upstreamBroken.set(true);
            }
        });
        server.start();
        return server;
    }

    @Test
    @DisplayName("chat → 请求体带 max_tokens，且解析出 content")
    void chat_sendsMaxTokens() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = fakeOpenAi(body, "{\"choices\":[{\"message\":{\"content\":\"通过\"}}]}");
        try {
            AiChatClient client = clientFor(1234);

            String out = client.chat(AiScene.TITLE, baseUrl(server), "sk-test", "deepseek-chat", 0.5, "你是审核员", "正文");

            assertEquals("通过", out);
            assertTrue(body.get().contains("\"max_tokens\":1234"),
                    "请求体必须带 max_tokens，实际：" + body.get());
            assertTrue(body.get().contains("你是审核员"), "system 消息没发出去");
            // 非流式请求不应带 stream:true（不同实现可能省略默认值，因此只禁止 true）
            assertFalse(body.get().contains("\"stream\":true"),
                    "非流式调用不应带 stream:true，实际：" + body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("chatStream → 请求体带 max_tokens，并逐段回调增量文本")
    void chatStream_sendsMaxTokens() throws Exception {
        // 只约束 text/event-stream：真实 OpenAI 兼容服务端声明流式响应即用它，
        // 它也是框架版（Spring AI）选择 SSE 解码器的依据。声明为 application/json 时，
        // 框架版会按「一行一个 JSON」解析，遇到 data: 前缀直接失败
        // （JsonParseException: Unrecognized token 'data'）。手写版自行按 data: 前缀逐行读取、
        // 对 Content-Type 不敏感，该宽容行为单独在 AiClientTest 中约束，
        // 这是两个实现的一处已知差异。
        AtomicReference<String> body = new AtomicReference<>();
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"错\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"别字\"}}]}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = fakeOpenAi(body, sse, "text/event-stream");
        try {
            AiChatClient client = clientFor(777);
            List<String> chunks = new ArrayList<>();

            client.chatStream(AiScene.TITLE, baseUrl(server), "sk-test", "deepseek-chat", 0.5, "sys", "user", chunks::add);

            assertEquals(String.join("", chunks), "错别字", "增量文本要按顺序回调，实际：" + chunks);
            assertTrue(body.get().contains("\"max_tokens\":777"),
                    "流式请求体同样要带 max_tokens，实际：" + body.get());
            assertTrue(body.get().contains("\"stream\":true"), "实际：" + body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("非流式：解析上游返回的 usage 并上报 token")
    void chat_reportsTokenUsage() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = fakeOpenAi(body,
                "{\"choices\":[{\"message\":{\"content\":\"通过\"}}],"
                        + "\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":300}}");
        try {
            AiChatClient client = clientFor(100);

            client.chat(AiScene.TITLE, baseUrl(server), "sk-test", "deepseek-chat", 0.5, "s", "u");

            assertEquals(1200.0, tokenCount("prompt"), 0.001, "输入用量没上报，管理端的消耗统计会偏小");
            assertEquals(300.0, tokenCount("completion"), 0.001, "输出用量没上报");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("流式：请求体要求上游回传用量，收尾分片的 usage 要上报且不当作正文回调")
    void chatStream_reportsTokenUsage() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        // 收尾分片的 choices 为空数组 —— 这是 include_usage 的规范形态，也是最容易写错的一处：
        // 按「choices[0] 必存在」解析会在这里取到空正文，按「有正文才回调」处理才正确
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"甲\"}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":22}}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = fakeOpenAi(body, sse, "text/event-stream");
        try {
            AiChatClient client = clientFor(100);
            List<String> chunks = new ArrayList<>();

            client.chatStream(AiScene.TITLE, baseUrl(server), "sk-test", "deepseek-chat", 0.5, "s", "u", chunks::add);

            assertTrue(body.get().contains("stream_options"),
                    "流式请求体必须带 stream_options，否则上游不回传用量，"
                            + "而流式是本项目文本消耗的主要来源。实际：" + body.get());
            assertTrue(body.get().contains("include_usage"),
                    "stream_options 必须打开 include_usage。实际：" + body.get());
            assertEquals(List.of("甲"), chunks,
                    "只带用量的收尾分片没有正文，不应回调出去。实际回调：" + chunks);
            assertEquals(11.0, tokenCount("prompt"), 0.001, "流式输入用量没上报");
            assertEquals(22.0, tokenCount("completion"), 0.001, "流式输出用量没上报");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("上游不返回 usage 时不报错、记 0：兼容服务未必都返回该字段")
    void chat_withoutUsage_isSafe() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = fakeOpenAi(body, "{\"choices\":[{\"message\":{\"content\":\"通过\"}}]}");
        try {
            AiChatClient client = clientFor(100);

            String out = client.chat(AiScene.TITLE, baseUrl(server), "sk-test", "deepseek-chat", 0.5, "s", "u");

            assertEquals("通过", out, "缺少 usage 字段不影响正文解析");
            assertEquals(0.0, tokenCount("prompt"), 0.001);
            assertEquals(0.0, tokenCount("completion"), 0.001);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("chatStream → 服务端非 2xx 要抛业务异常，且不把错误体当正文回调出去")
    void chatStream_httpError_throws() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = "{\"error\":\"invalid api key\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        try {
            AiChatClient client = clientFor(100);
            List<String> chunks = new ArrayList<>();

            // 断言具体异常与错误码，而不是「抛出即通过」：
            // 后者连 NPE 都会算作预期失败，等于该测试没有约束任何行为
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    client.chatStream(AiScene.TITLE, baseUrl(server), "sk-bad", "deepseek-chat", 0.5, "s", "u", chunks::add));

            assertEquals(ErrorCode.AI_GENERATE_FAIL, ex.getErrorCode(),
                    "401 必须转成对用户可解释的业务错误，而不是把三方异常抛到接口层");
            assertTrue(chunks.isEmpty(),
                    "非 2xx 的响应体不该被当成正常流回调出去，实际回调了：" + chunks);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("chatStream → onChunk 抛取消异常：立刻停止回调，并把异常原样抛出")
    void chatStream_cancelledByConsumer_stopsCallbacks() throws Exception {
        HttpServer server = fakeSlowStream(new AtomicInteger(), new AtomicBoolean(), 400, 20);
        try {
            AiChatClient client = clientFor(100);
            List<String> chunks = new ArrayList<>();

            // 第 2 段之后模拟用户点击「停止」
            StreamCancelledException ex = assertThrows(StreamCancelledException.class, () ->
                    client.chatStream(AiScene.TITLE, baseUrl(server), "sk-test", "deepseek-chat", 0.5, "s", "u", chunk -> {
                        chunks.add(chunk);
                        if (chunks.size() == 2) {
                            throw new StreamCancelledException("模拟用户点了停止");
                        }
                    }));

            assertEquals("模拟用户点了停止", ex.getMessage(),
                    "取消异常必须原样抛出 —— 被包装成业务异常的话，调用方会去退一次不该退的额度");
            assertEquals(2, chunks.size(),
                    "抛出之后不能再回调：这就是「停止」的语义，实际回调了 " + chunks);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("chatStream → 取消之后**上游的 HTTP 请求也要断**（模型那边得真的停）")
    void chatStream_cancelledByConsumer_closesUpstream() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        AtomicBoolean upstreamBroken = new AtomicBoolean();
        HttpServer server = fakeSlowStream(sent, upstreamBroken, 400, 40);
        try {
            AiChatClient client = clientFor(100);
            List<String> chunks = new ArrayList<>();

            assertThrows(StreamCancelledException.class, () ->
                    client.chatStream(AiScene.TITLE, baseUrl(server), "sk-test", "deepseek-chat", 0.5, "s", "u", chunk -> {
                        chunks.add(chunk);
                        if (chunks.size() == 2) {
                            throw new StreamCancelledException("模拟用户点了停止");
                        }
                    }));

            // 假服务端会持续写入。客户端若确实断开，其 write 会失败：
            // 这是「模型侧也已停止」唯一可观测的证据；仅观察客户端不再回调，只能证明前端已停止
            long deadline = System.currentTimeMillis() + 5000;
            while (!upstreamBroken.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(upstreamBroken.get(),
                    "上游连接没断：服务端在客户端取消后仍写了 " + sent.get() + " 段。"
                            + "说明「停止」只停了前端，模型那边还在把这个字生成完（白烧输出 token）");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("没配 Key → 抛业务异常，不发请求")
    void blankApiKey_throws() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = fakeOpenAi(body, "{}");
        try {
            AiChatClient client = clientFor(100);

            BusinessException ex = assertThrows(BusinessException.class, () ->
                    client.chat(AiScene.TITLE, baseUrl(server), "  ", "deepseek-chat", 0.5, "s", "u"));

            assertEquals(ErrorCode.AI_GENERATE_FAIL, ex.getErrorCode());
            assertNull(body.get(), "空 Key 不该真的发出请求");
        } finally {
            server.stop(0);
        }
    }
}
