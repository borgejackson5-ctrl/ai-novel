package com.ainovel.integration;

import com.ainovel.common.constant.SseConstant;
import com.ainovel.support.FakeOpenAiServer;
import com.ainovel.support.IntegrationTest;
import com.ainovel.support.IntegrationTestBase;
import com.ainovel.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.Socket;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 链路：以假的「OpenAI 兼容」服务端作为模型，把最长的两条链路纳入集成测试。
 *
 * <p>该段长期空白是有代价的：项目中问题最集中的部分就是流式生成，
 * 收尾结果（正常完成 / 被中断 / 上游失败）、扣费与退款、失败时给用户的文案，
 * 都属于只有真实运行才能观察到的内容。调用真实模型既产生费用又不稳定，
 * {@code @MockBean} 又会跳过整个 Spring AI 层，而解析 SSE、包装异常恰由该层完成。
 * 因此这里采用第三种做法：换成由测试控制的进程，HTTP 真实、SSE 真实、异常也真实。
 *
 * <p>配置的切换方式：向 {@code t_ai_config}（id=1）写入一行指向假服务。
 * 应用先读库、读不到才回退 yaml，读库这一层没有缓存，因此改完立即生效。
 * 自带 Key 的判定只依据「配置中是否存在 Key」，这里提供了 Key，因此走真实调用
 * 而不是本地演示内容。
 */
@IntegrationTest
@DisplayName("AI 链路：流式生成 / 收尾结果 / 扣费退款")
class AiStreamIntegrationTest extends IntegrationTestBase {

    /**
     * 整个 JVM 共用一个假模型。端口保持稳定是有意为之：{@code AiChatClientFactory} 按
     * 「地址 + 模型 + Key 指纹」缓存客户端，若桩更换端口，缓存中会留有一个指向已失效端口的实例。
     */
    private static final FakeOpenAiServer MODEL = FakeOpenAiServer.start();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void pointPlatformConfigAtFakeModel() {
        jdbc.update("DELETE FROM t_ai_config WHERE id = 1");
        jdbc.update("""
                INSERT INTO t_ai_config (id, base_url, api_key, model, temperature, mock_enabled,
                                         create_time, update_time, is_deleted)
                VALUES (1, ?, 'sk-stub-key', ?, 0.8, 0, NOW(), NOW(), 0)
                """, MODEL.baseUrl(), MODEL.model());
        MODEL.failWith(0);
        MODEL.chunksPerStream(8);
    }

    @AfterAll
    static void stopFakeModel() {
        MODEL.stop();
    }

    @Test
    @DisplayName("流式生成真打了模型：拿到内容、扣了额度、收尾是 done")
    void generateStreamUsesRealHttpModel() throws Exception {
        String token = login("user", "user123");
        String usageKey = usageKey();
        redis.delete(usageKey);
        int callsBefore = MODEL.callCount();
        double doneBefore = sseOutcomeCount("done");

        HttpResponse<String> resp = call("GET",
                "/ai/generate/stream?type=TITLE&input=" + encode("雨夜铜钱"), null, asUser(token));

        assertEquals(200, resp.statusCode(), () -> "响应体=" + head(resp.body()));
        assertTrue(resp.body().contains("第1段流式内容"),
                () -> "没拿到假模型的内容（说明没走真实调用或流被截断）：" + head(resp.body()));
        assertTrue(resp.body().contains("第8段流式内容"), () -> "流没收全：" + head(resp.body()));
        assertTrue(MODEL.callCount() > callsBefore, "压根没调用模型");

        // 扣费字数写在响应头中（开流前即已确定，见 AiController）
        String charged = resp.headers().firstValue(SseConstant.HEADER_CHARGED_UNITS).orElse("");
        assertTrue(charged.matches("\\d+") && Long.parseLong(charged) > 0, "响应头没给扣费字数：" + charged);

        // 额度确实记入 Redis（按字数、带日期后缀的日键）
        String used = redis.opsForValue().get(usageKey);
        assertTrue(used != null && Long.parseLong(used) > 0, "额度没有扣：" + used);

        waitUntil(Duration.ofSeconds(10), "sse_stream{outcome=done} 涨了",
                () -> sseOutcomeCount("done") > doneBefore);
    }

    @Test
    @DisplayName("调用指标带的是真实模型名（能一眼看出没走 mock / 降级）")
    void aiCallMetricCarriesConfiguredModel() throws Exception {
        String token = login("user", "user123");
        double before = aiCallCount("chatStream", MODEL.model());

        HttpResponse<String> resp = call("GET",
                "/ai/generate/stream?type=TITLE&input=" + encode("雨夜铜钱"), null, asUser(token));
        assertEquals(200, resp.statusCode());

        waitUntil(Duration.ofSeconds(10), "ai_call{scene=chatStream,model=" + MODEL.model() + "} 涨了",
                () -> aiCallCount("chatStream", MODEL.model()) > before);
    }

    @Test
    @DisplayName("用户中途停（客户端断开）记成 cancelled，而不是 done 也不是 error")
    void clientDisconnectIsRecordedAsCancelled() throws Exception {
        String token = login("user", "user123");
        // 拉长到足以在中途断开：30 段 × 60 毫秒
        MODEL.chunksPerStream(30);
        double cancelledBefore = sseOutcomeCount("cancelled");
        double doneBefore = sseOutcomeCount("done");

        // 裸 socket：收到第一块即关闭连接，等价于用户点击「停止」或关闭页面
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(("GET /ai/generate/stream?type=TITLE&input=" + encode("雨夜铜钱")
                    + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + token
                    + "\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            byte[] buf = new byte[256];
            assertTrue(socket.getInputStream().read(buf) > 0, "第一块都没收到");
        }

        waitUntil(Duration.ofSeconds(20), "sse_stream{outcome=cancelled} 涨了",
                () -> sseOutcomeCount("cancelled") > cancelledBefore);
        // 关键点：不能被记为 done，仅验证「有指标增长」不足以说明收尾结果分类正确
        assertEquals(doneBefore, sseOutcomeCount("done"), 0.0001,
                "客户端都走了，这次生成却被记成了 done");
    }

    @Test
    @DisplayName("上游报错：给用户一句人话（400），并把扣掉的额度按原数退回去")
    void upstreamFailureRefundsQuota() throws Exception {
        String token = login("user", "user123");
        String usageKey = usageKey();
        redis.delete(usageKey);

        MODEL.failWith(500);
        HttpResponse<String> resp = call("GET",
                "/ai/generate/stream?type=TITLE&input=" + encode("雨夜铜钱"), null, asUser(token));

        assertEquals(400, resp.statusCode(),
                () -> "上游失败要给 400 + 提示，实际 " + resp.statusCode() + " body=" + head(resp.body()));
        JsonNode body = JSON.readTree(resp.body());
        assertFalse(body.path("msg").asText().isBlank(), "要有给用户看的提示，不能空着");

        // 失败退额度：按扣除数量原额退回，退完后该键为 0 或不存在
        waitUntil(Duration.ofSeconds(10), "扣掉的额度退回去了", () -> {
            try {
                String used = redis.opsForValue().get(usageKey);
                return used == null || "0".equals(used);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ==================== 小工具 ====================

    /** 与 AiConfigServiceImpl 的键口径一致：ai:user:usage:{userId}:{yyyy-MM-dd} */
    private String usageKey() {
        return "ai:user:usage:" + TestData.userId(jdbc, "user") + ":" + LocalDate.now();
    }

    private double sseOutcomeCount(String outcome) {
        return sumMetric("ainovel_sse_stream_total", "outcome=\"" + outcome + "\"");
    }

    private double aiCallCount(String scene, String model) {
        return sumMetric("ainovel_ai_call_total", "scene=\"" + scene + "\"", "model=\"" + model + "\"");
    }

    /**
     * 从 /actuator/prometheus 中把某条指标（可带若干标签过滤）累加。
     *
     * <p>端点不可达时立即报错，不得静默返回 0：早先的版本吞掉异常、读不到即返回 0，
     * 结果断言只表现为「指标没有增长」，真正的原因（prometheus 端点未暴露）
     * 被掩盖了两轮排查。
     */
    private double sumMetric(String name, String... tags) {
        HttpResponse<String> resp;
        try {
            resp = call("GET", "/actuator/prometheus", null, guest());
        } catch (Exception e) {
            throw new IllegalStateException("拉取 /actuator/prometheus 失败：" + e, e);
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(
                    "/actuator/prometheus 返回 " + resp.statusCode() + "：" + head(resp.body()));
        }
        double sum = 0;
        for (String line : resp.body().split("\n")) {
            if (!line.startsWith(name + "{")) {
                continue;
            }
            boolean matches = true;
            for (String tag : tags) {
                if (!line.contains(tag)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                sum += Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
            }
        }
        return sum;
    }

    @Test
    @DisplayName("流式请求只被限流记一次 —— ASYNC 分发那一遍不该重复计数")
    void streamCountsOnceForRateLimit() throws Exception {
        String token = login("user", "user123");
        long userId = TestData.userId(jdbc, "user");
        String userKey = "rl:ai:generate:u:" + userId;
        String allKeysOfThisPoint = "rl:ai:generate:*";
        redis.delete(redis.keys(allKeysOfThisPoint));

        HttpResponse<String> resp = call("GET",
                "/ai/generate/stream?type=TITLE&input=" + encode("雨夜铜钱"), null, asUser(token));
        assertEquals(200, resp.statusCode(), () -> "响应体=" + head(resp.body()));

        // 只等计数 key 出现是不够的：ASYNC 分发发生在 SSE 收尾之后（容器会再走一遍 preHandle），
        // 完全可能晚于「客户端读完响应」这一时刻。不等这段时间时，即使去掉跳过 ASYNC 的
        // 那层包装，这条用例同样通过，等于没有验证。
        Thread.sleep(1500);

        // 判据必须跨维度求和，不能只看用户那条计数：
        // ASYNC 分发那一遍确实会再执行一次拦截器，只是那时 Sa-Token 的上下文已经不存在
        // （Filter 不在 ASYNC 分发中执行），拿不到登录态，维度退化成客户端 IP，
        // 多出来的那一次计到 ip: 的 key 上。只看 u: 那条会得出「没问题」的错误结论。
        int total = redis.keys(allKeysOfThisPoint).stream()
                .mapToInt(k -> Integer.parseInt(String.valueOf(redis.opsForValue().get(k))))
                .sum();
        assertEquals(1, total,
                "一次流式请求只该消耗 1 次限流额度。是 2 说明 ASYNC 那遍也被计了"
                        + "（多半不在 u: 那条上，而是退化成 ip: 维度）");
        // 同时约束维度未退化：登录用户的请求必须记在其自身名下
        assertEquals("1", redis.opsForValue().get(userKey),
                "带 token 的请求却记到了别的维度上");
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String head(String body) {
        return body == null ? "null" : body.substring(0, Math.min(200, body.length()));
    }
}
