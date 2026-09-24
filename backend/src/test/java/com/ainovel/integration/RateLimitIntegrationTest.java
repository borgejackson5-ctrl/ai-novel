package com.ainovel.integration;

import com.ainovel.support.IntegrationTest;
import com.ainovel.support.IntegrationTestBase;
import com.ainovel.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口级限流：超限返回什么，以及它不应影响谁。
 *
 * <p>必须请求真实接口的原因：限流生效的前提是「拦截器处于正确的顺序、key 拼接正确、
 * 维度可获取」，这三件事单测全部观察不到。尤其是维度：游客与登录用户走两条计数，
 * 单测中没有登录态可言。
 *
 * <p>限流计数存于 Redis，而该 Redis 容器在整个测试 JVM 内共用：不清理时，
 * 本类打满的额度会影响到其他类（{@code HttpLayerIntegrationTest} 每个用例都要登录一次，
 * 一旦 {@code /auth/login} 的额度被占满，它就会以难以理解的方式失败）。因此每个用例结束清一次。
 */
@IntegrationTest
@DisplayName("接口级限流：429 形态、维度隔离、窗口自愈")
class RateLimitIntegrationTest extends IntegrationTestBase {

    /** {@code /auth/login} 的注解限额是 30 次/60 秒 */
    private static final int LOGIN_LIMIT = 30;

    private static final String LOGIN_BODY = "{\"identifier\":\"user\",\"password\":\"user123\"}";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("超过限额 → 429 + 错误码 10004 + Retry-After（真打 10 次，证明计数真的在涨）")
    void overLimitReturns429() throws Exception {
        for (int i = 1; i <= LOGIN_LIMIT; i++) {
            HttpResponse<String> ok = call("POST", "/auth/login", LOGIN_BODY, Map.of());
            assertThat(ok.statusCode()).as("第 %d 次登录本该放行", i).isEqualTo(200);
        }

        HttpResponse<String> blocked = call("POST", "/auth/login", LOGIN_BODY, Map.of());

        // 状态码必须语义化：前端与网关需能区分「请求参数错误」(400) 与「请求过于频繁」(429)，
        // 后者应退避重试，而不是让用户修改参数
        assertThat(blocked.statusCode()).isEqualTo(429);
        // 还需告知等待时长：窗口长度各限流点不同，只能由服务端给出
        assertThat(blocked.headers().firstValue("Retry-After")).contains("60");
        assertThat(blocked.body()).contains("\"code\":10004");
        // 文案需带秒数：仅提示「操作过于频繁」时用户会持续重试，进一步消耗额度
        assertThat(blocked.body()).contains("60 秒后再试");
    }

    @Test
    @DisplayName("维度隔离：登录用户的额度顶格，不该带累游客（两条独立计数）")
    void userAndGuestAreSeparateCounters() throws Exception {
        long userId = TestData.userId(jdbc, "user");
        String token = login("user", "user123");

        // 直接把「登录用户」这条计数置到上限：省去 120 次请求，同时这也验证了 key 的拼法
        // （名字与维度之间用冒号连接，前缀是 rl:）：拼错时此处即失效，测试立即失败
        redis.opsForValue().set("rl:category:u:" + userId, "120", Duration.ofSeconds(60));

        HttpResponse<String> asUser = call("GET", "/category/list", null, asUser(token));
        assertThat(asUser.statusCode()).as("登录用户这条计数已顶格").isEqualTo(429);

        HttpResponse<String> asGuest = call("GET", "/category/list", null, guest());
        assertThat(asGuest.statusCode())
                .as("游客按客户端 IP 计数，是另一条账 —— 被登录用户的额度带累说明维度没分开")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("窗口会自愈：计数消失后接口立刻恢复，不会永久限死")
    void windowRecovers() throws Exception {
        call("POST", "/auth/login", LOGIN_BODY, Map.of());
        String key = onlyRateLimitKey("rl:auth:login:*");

        redis.opsForValue().set(key, String.valueOf(LOGIN_LIMIT));
        assertThat(call("POST", "/auth/login", LOGIN_BODY, Map.of()).statusCode()).isEqualTo(429);

        // 窗口滚动（TTL 到期）等价于这条 key 消失
        redis.delete(key);
        assertThat(call("POST", "/auth/login", LOGIN_BODY, Map.of()).statusCode())
                .as("计数没了还继续拒绝，说明拒绝路径上另有状态没清")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("计数没有过期时间时会被补上 —— 否则那个维度会被永久限死")
    void missingTtlIsRepaired() throws Exception {
        call("POST", "/auth/login", LOGIN_BODY, Map.of());   // 先打一次，让计数 key 出现
        String key = onlyRateLimitKey("rl:auth:login:*");
        // 模拟被外部破坏：有计数但没有过期时间，这种 key 不会自行消失
        redis.opsForValue().set(key, "1");
        assertThat(redis.getExpire(key)).isEqualTo(-1L);

        assertThat(call("POST", "/auth/login", LOGIN_BODY, Map.of()).statusCode())
                .as("1 < 10，这次该放行").isEqualTo(200);

        assertThat(redis.getExpire(key))
                .as("放行时没补 TTL，这个 key 会永远留着 —— 计数一旦顶格，该用户就再也进不来了")
                .isGreaterThan(0L);
    }

    /**
     * 取某个限流点的 key。
     *
     * <p>不硬编码 IP：客户端地址可能是 IPv4 也可能是 IPv6，写死某个值会在更换机器时静默失效。
     * 只按前缀查找，并要求有且只有一个：出现两个说明 key 的拼法已变更。
     */
    private String onlyRateLimitKey(String pattern) {
        Set<String> keys = redis.keys(pattern);
        assertThat(keys).as("限流 key %s 应该只有一个，实际 %s", pattern, keys).hasSize(1);
        return keys.iterator().next();
    }
}
