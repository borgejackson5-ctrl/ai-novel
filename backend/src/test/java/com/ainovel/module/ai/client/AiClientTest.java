package com.ainovel.module.ai.client;

import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.module.ai.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 手写 RestClient 实现的契约测试：断言与 Spring AI 版完全相同
 * （见 {@link AiChatClientContract}）。
 *
 * <p>该类本身不放断言，其价值在于与另一个实现共用同一组用例；
 * 它同时是理解 Spring AI 的对照：同一行为两边各实现一次，差异即框架省略的内容。
 */
class AiClientTest extends AiChatClientContract {

    @Override
    AiChatClient createClient(AiProperties props) {
        // 指标门面用真实对象 + 内存 registry：它是无副作用的写入，不需要 mock
        AiClient client = new AiClient(props, new BusinessMetrics(new SimpleMeterRegistry()), HttpClient.newHttpClient());
        // @PostConstruct 仅在 Spring 容器中自动触发，测试中需手动调用（它负责创建两个超时不同的 RestClient）
        client.init();
        return client;
    }
    @Test
    @DisplayName("chatStream → 手写版对响应 Content-Type 不敏感（框架版要求 text/event-stream）")
    void chatStream_toleratesJsonContentType() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"错\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"别字\"}}]}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = fakeOpenAi(body, sse, "application/json");
        try {
            List<String> chunks = new ArrayList<>();

            clientFor(100).chatStream(baseUrl(server), "sk-test", "deepseek-chat", 0.5, "s", "u", chunks::add);

            assertEquals("错别字", String.join("", chunks),
                    "手写版按 data: 前缀逐行读，不依赖服务端声明的 Content-Type");
        } finally {
            server.stop(0);
        }
    }
}
