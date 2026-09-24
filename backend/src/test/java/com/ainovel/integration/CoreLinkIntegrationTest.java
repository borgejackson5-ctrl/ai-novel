package com.ainovel.integration;

import com.ainovel.module.search.service.SearchService;
import com.ainovel.support.IntegrationTest;
import com.ainovel.support.IntegrationTestBase;
import com.ainovel.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 四条真实中间件各自支撑的那一段链路：每一条都不是 mock 能证明的。
 *
 * <ul>
 *   <li><b>MySQL</b>：口令校验确实查询了数据库；书架重复添加确实只保留一行；</li>
 *   <li><b>Redis</b>：读详情确实把值写入缓存（而不是「调用了一个 mock，返回 true」）；</li>
 *   <li><b>RabbitMQ</b>：发布写下的 outbox 消息确实被投递出去（提交后由独立线程投递）；</li>
 *   <li><b>Elasticsearch</b>：同步一条小说之后，用真实接口可以搜到，同时证明 IK 分词在生效。</li>
 * </ul>
 */
@IntegrationTest
@DisplayName("真中间件链路：MySQL / Redis / RabbitMQ / Elasticsearch")
class CoreLinkIntegrationTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SearchService searchService;

    // ==================== MySQL ====================

    @Test
    @DisplayName("登录：口令错拿不到 token，口令对能拿到")
    void loginChecksPasswordAgainstDatabase() throws Exception {
        HttpResponse<String> wrong = call("POST", "/auth/login",
                "{\"identifier\":\"user\",\"password\":\"definitely-not-the-password\"}", Map.of());
        assertEquals(400, wrong.statusCode(), () -> "响应体=" + wrong.body());
        assertFalse(wrong.body().contains("token"), "口令错了还发 token：" + wrong.body());

        String token = login("user", "user123");
        assertFalse(token.isBlank());
    }

    @Test
    @DisplayName("书架：同一本书加两次只留一条（幂等，真唯一约束下也成立）")
    void bookshelfAddIsIdempotent() throws Exception {
        String token = login("user", "user123");
        long authorId = TestData.userId(jdbc, "user");
        TestData.PublishedBook book = TestData.publishedBook(jdbc, "书架幂等测试书", "简介。", authorId);
        long userId = TestData.userId(jdbc, "user");

        for (int i = 0; i < 2; i++) {
            HttpResponse<String> resp = call("POST", "/bookshelf/" + book.novelId(), null, asUser(token));
            assertEquals(200, resp.statusCode(), "第 " + (i + 1) + " 次加书架失败：" + resp.body());
        }

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_bookshelf WHERE user_id = ? AND novel_id = ? AND is_deleted = 0",
                Integer.class, userId, book.novelId());
        assertEquals(1, rows, "重复加同一本书留了多条记录");

        // 书架列表里也只看得到一条
        String listBody = call("GET", "/bookshelf/list", null, asUser(token)).body();
        long occurrences = listBody.split(String.valueOf(book.novelId()), -1).length - 1;
        assertEquals(1, occurrences, "书架列表里这本书出现了 " + occurrences + " 次");
    }

    // ==================== Redis ====================

    @Test
    @DisplayName("读作品详情真的写进了 Redis，第二次读命中缓存")
    void novelDetailIsCachedInRedis() throws Exception {
        String token = login("user", "user123");
        TestData.PublishedBook book = TestData.publishedBook(jdbc, "缓存测试书", "简介：用来验证缓存。",
                TestData.userId(jdbc, "user"));
        String cacheKey = "novel:detail:" + book.novelId();
        redis.delete(cacheKey);

        HttpResponse<String> first = call("GET", "/novel/" + book.novelId(), null, asUser(token));
        assertEquals(200, first.statusCode(), () -> "响应体=" + first.body());
        assertTrue(Boolean.TRUE.equals(redis.hasKey(cacheKey)),
                "读完之后缓存里没有 " + cacheKey + "，说明缓存没生效（或键名变了）");

        String cached = redis.opsForValue().get(cacheKey);
        assertTrue(cached != null && cached.contains(book.title()), "缓存里的值不对：" + cached);

        // 第二次读仍返回相同内容（经缓存路径返回）
        HttpResponse<String> second = call("GET", "/novel/" + book.novelId(), null, asUser(token));
        assertEquals(200, second.statusCode());
        assertEquals(JSON.readTree(first.body()).path("data").path("title").asText(),
                JSON.readTree(second.body()).path("data").path("title").asText());
    }

    // ==================== Elasticsearch ====================

    @Test
    @DisplayName("同步到 ES 之后，用真接口搜得到（关键词命中 + 只返回上架作品）")
    void novelBecomesSearchableAfterEsSync() throws Exception {
        String title = "长安旧事集成测试书";
        TestData.PublishedBook book = TestData.publishedBook(jdbc, title,
                "讲述一位书生在长安城遇到狐妖的故事。", TestData.userId(jdbc, "user"));

        assertTrue(searchService.syncOne(book.novelId()), "同步单本到 ES 失败");

        // ES 为近实时：写入后需等待一次 refresh 才可检索，因此这里必须轮询而不能固定 sleep
        String keyword = URLEncoder.encode("长安城", StandardCharsets.UTF_8).replace("+", "%20");
        waitUntil(Duration.ofSeconds(20), "ES 索引里出现《" + title + "》", () -> {
            try {
                HttpResponse<String> resp = call("GET",
                        "/novel/search?keyword=" + keyword + "&page=1&size=20", null, guest());
                return resp.statusCode() == 200 && resp.body().contains(title);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        // 未上架（下架/待审）的作品不应被搜出：这一条验证 filter status = 1 确实生效
        jdbc.update("UPDATE t_novel SET status = 0 WHERE id = ?", book.novelId());
        searchService.syncOne(book.novelId());
        waitUntil(Duration.ofSeconds(20), "下架后《" + title + "》应从结果里消失", () -> {
            try {
                HttpResponse<String> resp = call("GET",
                        "/novel/search?keyword=" + keyword + "&page=1&size=20", null, guest());
                return !resp.body().contains(title);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ==================== RabbitMQ ====================

    @Test
    @DisplayName("发布作品：outbox 与业务同事务落库，随后真的被投出去")
    void publishWritesOutboxAndGetsDelivered() throws Exception {
        String token = login("user", "user123");
        Long maxIdBefore = jdbc.queryForObject("SELECT IFNULL(MAX(id), 0) FROM t_mq_outbox", Long.class);
        assertTrue(maxIdBefore != null);

        String body = """
                {"title":"集成测试发布书","categoryId":1,"intro":"集成测试用，别当真。",
                 "chapters":[{"title":"第一章","content":"这是发布出来的第一章正文，用来触发章节块同步。"}]}
                """;
        HttpResponse<String> resp = call("POST", "/novel/publish", body, asUser(token));
        assertEquals(200, resp.statusCode(), () -> "发布失败：" + resp.body());
        long novelId = JSON.readTree(resp.body()).path("data").path("id").asLong();
        assertTrue(novelId > 0);

        // 1) 待投消息与业务在同一事务中写入（不依赖事后补偿）
        Integer written = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_mq_outbox WHERE id > ?", Integer.class, maxIdBefore);
        assertTrue(written != null && written > 0, "发布没有往 outbox 写消息");

        // 2) 提交后由独立线程投递：这一条只有在真实 RabbitMQ 下才成立
        waitUntil(Duration.ofSeconds(30), "outbox 里出现投递成功的消息", () -> {
            Integer sent = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_mq_outbox WHERE id > ? AND status = 1", Integer.class, maxIdBefore);
            return sent != null && sent > 0;
        });

        // 3) 消息未丢失：新写入的每一条都仍在（已投递或待重投），没有消失的条目
        Integer alive = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_mq_outbox WHERE id > ?", Integer.class, maxIdBefore);
        assertEquals(written, alive, "outbox 条数变少了，说明消息被删了");

        // 4) 同时约束一个产品行为：发布出的书为「待审 + 下架」，读者侧不可见
        Integer status = jdbc.queryForObject("SELECT status FROM t_novel WHERE id = ?", Integer.class, novelId);
        Integer auditStatus = jdbc.queryForObject("SELECT audit_status FROM t_novel WHERE id = ?",
                Integer.class, novelId);
        assertEquals(0, status, "新发布的作品不该直接上架");
        assertEquals(0, auditStatus, "新发布的作品应该是待审状态");

        // 5) 审核消息正文需携带本行的 outbox 主键：消费端以它作为去重键。
        //    单测只能证明「save 调用时传入了该值」，无法证明「投递出去的 JSON 中包含它」，
        //    因为投递使用的是库中存储的 payload。这一条只有真实数据库 + 真实 broker 才能成立。
        List<Map<String, Object>> auditRows = jdbc.queryForList(
                "SELECT id, payload FROM t_mq_outbox WHERE id > ? AND routing_key = ?",
                maxIdBefore, "ai.novel.audit");
        assertFalse(auditRows.isEmpty(), "发布流程应当往审核队列投过消息");
        boolean anyWithOutboxId = auditRows.stream().anyMatch(row -> {
            long id = ((Number) row.get("id")).longValue();
            try {
                // 按 JSON 取值后再比较文本，不写成 contains("\"outboxId\":" + id)：
                // 项目口径是雪花 ID 一律序列化为 JSON 字符串，此处写入库中的即为
                // {"novelId":"…","chapterId":null,"outboxId":"…"}，按数字比较会产生误报。
                return String.valueOf(id).equals(
                        JSON.readTree(String.valueOf(row.get("payload"))).path("outboxId").asText());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertTrue(anyWithOutboxId, () -> "审核消息正文里没带上自己的 outbox 主键，消费端去重会失效：" + auditRows);
    }
}
