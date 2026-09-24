package com.ainovel.integration;

import com.ainovel.support.IntegrationTest;
import com.ainovel.support.IntegrationTestBase;
import com.ainovel.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 层：请求头、内容协商、异常处理器、鉴权拦截器，即单测无法触及的那一层。
 *
 * <p>该类中最重要的是第一个用例，它复现了复测中发现的真实缺陷：业务校验抛出的
 * {@code BusinessException}，在客户端只接受 {@code text/event-stream} 时会被内容协商吞掉，
 * 异常处理器自身也失败，最终返回 500 空响应体，前端只能显示「服务器错误」，
 * 看不到原本准备好的中文提示。
 *
 * <p>单测无法捕获该问题的原因：报错链条是「请求头 → 内容协商策略 → 异常解析器注册顺序」，
 * 全部属于 Spring MVC 层，而单测直接调用 Service 方法，没有请求头这一层。
 */
@IntegrationTest
@DisplayName("HTTP 层：业务异常形态 + 游客白名单")
class HttpLayerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private String userToken;

    @BeforeEach
    void loginAsDemoUser() throws Exception {
        userToken = login("user", "user123");
    }

    /** 入参足够短，一定会撞上「选中的内容太短了」那条业务校验 */
    private static final String TOO_SHORT_POLISH_BODY = "{\"content\":\"短\",\"mode\":\"EXPRESS\"}";

    // ==================== 业务异常的返回形态 ====================

    @Test
    @DisplayName("★ 只接受 text/event-stream 时，业务异常也要给出可读 JSON（不是 500 空体）")
    void sseBusinessErrorReturnsReadableJson() throws Exception {
        Map<String, String> headers = new HashMap<>(asUser(userToken));
        // 关键在于这一行：只接受 SSE，不接受 application/json
        headers.put("Accept", "text/event-stream");

        HttpResponse<String> resp = call("POST", "/ai/write/polish", TOO_SHORT_POLISH_BODY, headers);

        assertEquals(400, resp.statusCode(),
                () -> "期望 400 + 一句人话；实际 " + resp.statusCode() + "，响应体=" + quote(resp.body()));
        JsonNode body = JSON.readTree(resp.body());
        assertTrue(body.path("msg").asText().contains("太短"),
                () -> "提示语不对：" + resp.body());
    }

    @Test
    @DisplayName("不带 Accept 头时，同一条业务异常同样是 400 + JSON（修复没改坏标准路径）")
    void businessErrorWithoutAcceptIsStillJson() throws Exception {
        HttpResponse<String> resp = call("POST", "/ai/write/polish", TOO_SHORT_POLISH_BODY, asUser(userToken));

        assertEquals(400, resp.statusCode(), () -> "响应体=" + quote(resp.body()));
        assertTrue(JSON.readTree(resp.body()).path("msg").asText().contains("太短"));
    }

    @Test
    @DisplayName("接受任意类型（*/*）时也回 JSON —— 别让兜底策略只剩下 JSON 而把正常请求搞坏")
    void wildcardAcceptStillJson() throws Exception {
        Map<String, String> headers = new HashMap<>(asUser(userToken));
        headers.put("Accept", "*/*");

        HttpResponse<String> resp = call("POST", "/ai/write/polish", TOO_SHORT_POLISH_BODY, headers);

        assertEquals(400, resp.statusCode(), () -> "响应体=" + quote(resp.body()));
        assertTrue(JSON.readTree(resp.body()).path("msg").asText().contains("太短"));
    }

    @Test
    @DisplayName("参数校验（@Valid）失败也走同一条路：400 + 字段级人话，不是 500")
    void beanValidationFailureIsReadable() throws Exception {
        Map<String, String> headers = new HashMap<>(asUser(userToken));
        headers.put("Accept", "text/event-stream");

        // mode 为空 → PolishMode.of(null) 无法判定 → 「请选择要改的方式」
        HttpResponse<String> resp = call("POST", "/ai/write/polish",
                "{\"content\":\"这是一段够长的内容，用来绕开太短的校验。\"}", headers);

        assertEquals(400, resp.statusCode(),
                () -> "期望 400；实际 " + resp.statusCode() + "，响应体=" + quote(resp.body()));
        assertFalse(resp.body().isBlank(), "响应体不该是空的");
    }

    // ==================== 游客：能看什么、不能看什么 ====================

    @Test
    @DisplayName("游客（不带 token）能读内容类接口")
    void guestCanReadContentEndpoints() throws Exception {
        TestData.PublishedBook book = TestData.publishedBook(jdbc, "游客可见测试书",
                "简介：讲一个书生的故事。", TestData.userId(jdbc, "user"));

        List<String> readable = List.of(
                "/novel/page?pageNum=1&pageSize=2",
                "/novel/search?keyword=书生&pageNum=1&pageSize=2",
                "/category/list",
                "/rank/hot",
                "/novel/" + book.novelId(),
                "/novel/by-author/" + book.authorId(),
                "/chapter/page/" + book.novelId(),
                "/chapter/" + book.freeChapterId(),
                "/comment/page/" + book.novelId(),
                "/comment/chapter/" + book.freeChapterId()
        );

        for (String path : readable) {
            HttpResponse<String> resp = call("GET", path, null, guest());
            assertEquals(200, resp.statusCode(), path + " 应该对游客开放，实际 " + resp.statusCode());
        }
    }

    @Test
    @DisplayName("游客能读免费章正文（新用户不注册也能看第一章）")
    void guestCanReadFreeChapter() throws Exception {
        TestData.PublishedBook book = TestData.publishedBook(jdbc, "免费章测试书", "简介。", TestData.userId(jdbc, "user"));

        HttpResponse<String> resp = call("GET", "/chapter/" + book.freeChapterId() + "/content", null, guest());

        assertEquals(200, resp.statusCode(), () -> "响应体=" + quote(resp.body()));
        assertFalse(JSON.readTree(resp.body()).path("data").path("content").asText().isBlank());
    }

    @Test
    @DisplayName("游客读付费章正文 → 401（而不是 403 或 500），前端才知道该引他去登录")
    void guestCannotReadPaidChapter() throws Exception {
        TestData.PublishedBook book = TestData.publishedBook(jdbc, "付费章测试书", "简介。", TestData.userId(jdbc, "user"));

        HttpResponse<String> resp = call("GET", "/chapter/" + book.paidChapterId() + "/content", null, guest());

        assertEquals(401, resp.statusCode(), () -> "响应体=" + quote(resp.body()));
        assertEquals(401, JSON.readTree(resp.body()).path("code").asInt(), "业务码也要是 401，否则前端认不出是登录态问题");
    }

    @Test
    @DisplayName("游客读个人数据、调写接口 → 401")
    void guestBlockedOnPrivateAndWriteEndpoints() throws Exception {
        TestData.PublishedBook book = TestData.publishedBook(jdbc, "写接口测试书", "简介。", TestData.userId(jdbc, "user"));

        List<String> blocked = List.of(
                "/bookshelf/list",
                "/reader/progress",
                "/reader/preference",
                "/novel/mine",
                "/history/page?pageNum=1&pageSize=2",
                "/message/page?pageNum=1&pageSize=2",
                "/subscribe/unlock-status/" + book.novelId(),
                "/bookshelf/" + book.novelId() + "/status",
                "/chapter/author/" + book.novelId()
        );

        for (String path : blocked) {
            HttpResponse<String> resp = call("GET", path, null, guest());
            assertEquals(401, resp.statusCode(), path + " 不该对游客开放，实际 " + resp.statusCode());
        }

        // 写接口同样需要拦截（这几个与「详情」只差一个路径段，白名单中刻意未使用通配符）
        for (String path : List.of("/novel/publish", "/novel/save", "/novel/" + book.novelId() + "/read")) {
            HttpResponse<String> resp = call("POST", path, "{}", guest());
            assertEquals(401, resp.statusCode(), path + " 是写接口，不该对游客开放，实际 " + resp.statusCode());
        }
    }

    @Test
    @DisplayName("登录后，同一批接口都能打通（对照：401 是因为没登录，不是因为接口坏了）")
    void loggedInUserCanReachPrivateEndpoints() throws Exception {
        for (String path : List.of("/bookshelf/list", "/reader/progress", "/novel/mine")) {
            HttpResponse<String> resp = call("GET", path, null, asUser(userToken));
            assertEquals(200, resp.statusCode(), path + " 登录后应该能访问，实际 " + resp.statusCode());
        }
    }

    private static String quote(String body) {
        return body == null ? "null" : "\"" + body + "\"";
    }
}
