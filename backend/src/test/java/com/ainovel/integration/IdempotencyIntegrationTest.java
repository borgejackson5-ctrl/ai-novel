package com.ainovel.integration;

import com.ainovel.support.IntegrationTest;
import com.ainovel.support.IntegrationTestBase;
import com.ainovel.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等键：真实请求接口，验证「重放第一次的响应」确实发生。
 *
 * <p>难点在于判据必须有区分度。本项目的下单/支付/解锁本来就通过唯一索引与状态机实现了幂等
 * （重复解锁返回原单、已支付直接返回），因此「请求两次拿到同一个订单号」不能证明幂等键生效，
 * 不装该设施也是同样结果。
 *
 * <p>因此这里以充值接口自带的 {@code reused} 字段作为探针：
 * <ul>
 *   <li>第一次：{@code reused=false}（新建单）；</li>
 *   <li>带同一个键再来一次：响应与第一次逐字相同（{@code reused} 仍为 false），
 *       说明未进入业务，属于重放；</li>
 *   <li>换一个新键再来一次：走真实业务，复用待支付单，{@code reused=true}。</li>
 * </ul>
 * 第三条是关键对照：若第二条实际也进入了业务，其值会变为 true。只看前两条，
 * 一个「什么都不做的切面」也能让测试通过。
 */
@IntegrationTest
@DisplayName("幂等键：重放响应、向后兼容、键的作用域")
class IdempotencyIntegrationTest extends IntegrationTestBase {

    private static final String RECHARGE_BODY = "{\"amount\":100}";

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private String token;

    @BeforeEach
    void cleanStart() throws Exception {
        token = login("user", "user123");
        userId = TestData.userId(jdbc, "user");
        // 起点需干净：该账号遗留的待支付单会让「第一次」直接进入复用分支（reused=true），
        // 探针随之失效
        jdbc.update("UPDATE t_recharge_order SET status = 2 WHERE user_id = ? AND status = 0", userId);
    }

    @AfterEach
    void clearIdempotencyKeys() {
        Set<String> keys = redis.keys("idem:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("同一个键打两次 → 第二次是重放（响应逐字相同），且没进业务")
    void sameKeyReplaysIdenticalResponse() throws Exception {
        String key = "it-idem-" + System.nanoTime();

        HttpResponse<String> first = call("POST", "/coin/recharge", RECHARGE_BODY, idempotentHeaders(key));
        assertEquals(200, first.statusCode(), () -> "响应体=" + first.body());
        assertTrue(first.body().contains("\"reused\":false"),
                () -> "起点没清干净（第一次就复用了旧单），探针会失效：" + first.body());

        HttpResponse<String> second = call("POST", "/coin/recharge", RECHARGE_BODY, idempotentHeaders(key));
        assertEquals(first.body(), second.body(),
                "同一个键的第二次必须是**重放**：响应与第一次逐字相同（含 remainSeconds）");

        // 关键对照：换新键 ⇒ 走真实业务 ⇒ 复用待支付单。它证明上一条并非「业务本来就幂等」
        HttpResponse<String> third = call("POST", "/coin/recharge", RECHARGE_BODY,
                idempotentHeaders(key + "-another"));
        assertTrue(third.body().contains("\"reused\":true"),
                () -> "换了新键却还是 reused=false —— 那说明业务没跑，判据不成立：" + third.body());

        // 键确实写入 Redis（前缀 + 维度 + 方法 + 调用方提供的键）
        Set<String> keys = redis.keys("idem:*");
        assertTrue(keys != null && keys.stream().anyMatch(k -> k.endsWith(key)),
                () -> "没找到这次请求的幂等键，实际有：" + keys);
    }

    @Test
    @DisplayName("不带键 → 行为与以前完全一样（老客户端不受影响）")
    void withoutKeyBehavesAsBefore() throws Exception {
        HttpResponse<String> first = call("POST", "/coin/recharge", RECHARGE_BODY, asUser(token));
        HttpResponse<String> second = call("POST", "/coin/recharge", RECHARGE_BODY, asUser(token));

        assertTrue(first.body().contains("\"reused\":false"), () -> first.body());
        assertTrue(second.body().contains("\"reused\":true"),
                () -> "不带幂等键时应该走原有的「复用待支付单」逻辑，而不是被缓存住：" + second.body());
        assertNotEquals(first.body(), second.body());
    }

    @Test
    @DisplayName("键格式不合法 → 400（不静默忽略）")
    void malformedKeyRejected() throws Exception {
        HttpResponse<String> resp = call("POST", "/coin/recharge", RECHARGE_BODY, idempotentHeaders("ab"));

        assertEquals(400, resp.statusCode(),
                "键不合法却照常执行，等于告诉调用方「你的幂等生效了」——实际没有：" + resp.body());
        assertTrue(resp.body().contains("幂等键"), () -> resp.body());
    }

    @Test
    @DisplayName("键的作用域到方法：同一个键打在另一个接口上，不会读到前一个接口的结果")
    void keyIsScopedToEndpoint() throws Exception {
        String key = "it-idem-scope-" + System.nanoTime();

        HttpResponse<String> recharge = call("POST", "/coin/recharge", RECHARGE_BODY, idempotentHeaders(key));
        assertEquals(200, recharge.statusCode(), () -> recharge.body());
        String orderNo = JSON.readTree(recharge.body()).path("data").path("orderNo").asText();
        assertFalse(orderNo.isEmpty(), () -> "拿不到订单号：" + recharge.body());

        // 同一个键、另一个接口：必须真的执行取消，而不是重放充值的结果
        HttpResponse<String> cancel = call("POST", "/coin/order/" + orderNo + "/cancel", null,
                idempotentHeaders(key));
        assertEquals(200, cancel.statusCode(), () -> cancel.body());
        assertFalse(cancel.body().contains("reused"),
                () -> "取消订单的响应里出现了充值才有的字段 —— 键没有按方法隔离：" + cancel.body());
    }

    private Map<String, String> idempotentHeaders(String key) {
        Map<String, String> headers = new HashMap<>(asUser(token));
        headers.put("Idempotency-Key", key);
        return headers;
    }
}
