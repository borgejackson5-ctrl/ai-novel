package com.ainovel.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.ainovel.module.search.service.SearchService;
import com.ainovel.support.IntegrationTest;
import com.ainovel.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 底座自检：四个中间件是否确实连通。
 *
 * <p>该测试不验业务，只验「环境是真实的」。它的意义在于把「底座故障」与
 * 「业务写错」分开，否则集成测试一旦失败，第一反应总是怀疑刚写的断言，
 * 而不是怀疑容器未启动。各查一件最基础的事项：
 *
 * <ul>
 *   <li>MySQL：建表脚本确实执行了（表存在、分类种子数据存在），验证 init.sql 挂载成功；</li>
 *   <li>Redis：可写可读（同时证明口令匹配）；</li>
 *   <li>RabbitMQ：能获取一个真实连接；</li>
 *   <li>ES：索引已按实体映射创建、可查到条数（证明可连通且 mapping 生效）；</li>
 *   <li>HTTP：随机端口已就绪，健康检查通过，后续接口测试依赖它。</li>
 * </ul>
 */
@IntegrationTest
@DisplayName("集成测试底座：四个真中间件与 HTTP 端口")
class IntegrationSmokeTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Autowired
    private SearchService searchService;

    @Autowired
    private ElasticsearchClient esClient;

    /** 随机端口模式下它自带 base url，路径使用相对写法即可 */
    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("MySQL：init.sql 真的执行了（26 张表 + 7 条分类种子）")
    void mysqlSchemaInitialized() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'ai_drama'",
                Integer.class);
        assertNotNull(tables);
        // 26 为 sql/init.sql 中 CREATE TABLE 的条数；断言下限而非等号，后续新增表时无需修改此处
        assertTrue(tables >= 26, "业务表数量不对（实际 " + tables + " 张）—— init.sql 可能没执行");

        Integer categories = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_category", Integer.class);
        assertEquals(7, categories, "分类种子数据不全，init.sql 的 INSERT 可能失败了");
    }

    @Test
    @DisplayName("Redis：能写能读（口令也对得上）")
    void redisWorks() {
        String key = "it:smoke:" + System.nanoTime();
        redis.opsForValue().set(key, "ok", 1, TimeUnit.MINUTES);
        assertEquals("ok", redis.opsForValue().get(key));
        assertEquals(Boolean.TRUE, redis.delete(key));
    }

    @Test
    @DisplayName("RabbitMQ：能拿到真连接")
    void rabbitmqWorks() {
        try (Connection connection = connectionFactory.createConnection()) {
            assertTrue(connection.isOpen(), "RabbitMQ 连接没有打开");
        }
    }

    /**
     * 查条目数只能证明可连通。这里真正要约束的是所用镜像：
     * ES 为本地构建（官方 8.18.1 + IK 中文分词插件），若镜像构建失败而退回官方原版，
     * 索引仍能创建、查询也不报错，只是中文检索会静默退化为按字切分。
     * 因此用一次 _analyze 直接输出分词结果：IK 会切出长度大于 1 的词，
     * 未安装插件的标准分词器对纯中文只会切分成单字。
     */
    @Test
    @DisplayName("Elasticsearch：索引建好了，且跑的是带 IK 分词的那个镜像")
    void elasticsearchWorks() throws Exception {
        searchService.ensureIndex();

        List<String> tokens = esClient.indices()
                .analyze(a -> a.index("novel").analyzer("ik_max_word").text("红楼梦"))
                .tokens().stream().map(t -> t.token()).toList();

        assertFalse(tokens.isEmpty(), "ES 没切出任何词");
        assertTrue(tokens.stream().anyMatch(t -> t.length() > 1),
                "分词结果全是单字，说明这不是带 IK 插件的那个镜像：" + tokens);
    }

    @Test
    @DisplayName("HTTP 端口起来了，健康检查通")
    void httpPortUp() {
        ResponseEntity<String> resp = http.getForEntity("/actuator/health", String.class);
        assertTrue(resp.getStatusCode().is2xxSuccessful(), "健康检查返回 " + resp.getStatusCode());
        String body = resp.getBody();
        assertNotNull(body);
        assertTrue(body.contains("UP"), "健康检查没返回 UP：" + body);
    }
}
