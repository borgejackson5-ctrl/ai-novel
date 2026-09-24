package com.ainovel.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 集成测试的公共底座：启动四个真实中间件容器，并将连接信息注入 Spring 上下文。
 *
 * <p>引入真实中间件的原因：原有 500 余个测试全部基于 mock，而 mock 不会复现真实组件的缺陷。
 * 例如带 {@code Accept: text/event-stream} 时异常处理器自身失败并返回 500 空体这一缺陷，
 * 发生在 HTTP 层（请求头 + 内容协商 + 异常解析器的注册顺序），mock 层不存在该路径。
 *
 * <p>四个容器 MySQL / Redis / RabbitMQ / Elasticsearch 均为真实实例，镜像版本与
 * docker-compose.yml 一致；其中 ES 为本地构建的带 IK 中文分词插件的镜像，
 * 见 {@link #ES_IMAGE} 的说明。
 *
 * <p>容器为单例：在静态块中启动，整个 JVM 期间只启动一次，所有集成测试共用，
 * 由 Testcontainers 的 Ryuk 在进程结束时回收。若改为给每个测试类加
 * {@code @Testcontainers} + {@code @Container}，则每个类都要重启一次 ES
 * （单次启动耗时 40 余秒，四个类累计三到四分钟）。
 *
 * <p>数据库表结构不由代码创建：{@code sql/init.sql} 挂载到 MySQL 官方镜像的
 * {@code /docker-entrypoint-initdb.d/} 下，容器首次初始化时自动执行，与
 * docker-compose.yml 走同一条路径。建表脚本只有一份，可避免测试 schema 与线上 schema 不一致。
 */
public abstract class IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestBase.class);

    private static final String MYSQL_IMAGE = "mysql:8.0";

    private static final String REDIS_IMAGE = "redis:7-alpine";

    private static final String RABBIT_IMAGE = "rabbitmq:3.13-management";

    /**
     * ES 镜像由 compose 本地构建（官方 8.18.1 + IK 中文分词插件，
     * 见仓库根目录 {@code elasticsearch/Dockerfile}）。公共仓库中不存在该镜像，
     * Testcontainers 无法自动 pull，因此这里直接引用本地 tag，
     * 并在启动前校验其存在（缺失时给出可直接执行的构建命令，而非 docker 抛出的 pull access denied）。
     *
     * <p>替换为其他镜像（例如 CI 推送到私仓的镜像）时，设置环境变量 {@code IT_ES_IMAGE}。
     */
    private static final String ES_IMAGE =
            System.getenv().getOrDefault("IT_ES_IMAGE", "ai-novel-elasticsearch:latest");

    /** 与 application.yaml 中 redis 的默认口令一致：容器按同一口令启动，无需额外覆盖配置 */
    private static final String REDIS_PASSWORD = "123456";

    /** 数据库名固定为 ai_drama：init.sql 头部写定了 {@code USE ai_drama}，不可更改 */
    private static final String MYSQL_DATABASE = "ai_drama";

    /**
     * 建表脚本。必须使用仓库中的那一份脚本，不要复制到 test/resources：
     * 复制出的副本会与线上漂移，而 schema 漂移是「测试通过、线上失败」的常见来源。
     */
    private static final Path INIT_SQL = Path.of("..", "sql", "init.sql").toAbsolutePath().normalize();

    /** 发请求用的客户端：不跟随重定向（接口不应有重定向），连接超时 10 秒 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 断言响应体用的 JSON 解析器 */
    protected static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 随机端口。使用 JDK 的 HttpClient 直接请求接口时用它拼接 URL：
     * 这类测试的关键在于精确控制请求头（例如只接受 {@code text/event-stream}），
     * 使用 RestTemplate 等封装难以确定实际发送的内容。
     */
    @LocalServerPort
    protected int port;

    /** 限流计数存于 Redis；在底座中清理的原因见 {@link #clearRateLimitCounters()} */
    @Autowired
    protected StringRedisTemplate redis;

    static final MySQLContainer<?> MYSQL;

    static final GenericContainer<?> REDIS;

    static final RabbitMQContainer RABBITMQ;

    static final ElasticsearchContainer ELASTICSEARCH;

    static {
        long start = System.currentTimeMillis();
        tuneDockerApiVersion();
        assertDockerAvailable();
        assertLocalImageExists(ES_IMAGE);
        assertInitSqlExists();

        MYSQL = new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
                .withDatabaseName(MYSQL_DATABASE)
                // 与 compose 同一路径：MySQL 官方镜像会自动执行该目录下的 .sql
                .withCopyFileToContainer(MountableFile.forHostPath(INIT_SQL),
                        "/docker-entrypoint-initdb.d/init.sql")
                .withCommand("--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_general_ci",
                        "--default-time-zone=+08:00");

        REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

        RABBITMQ = new RabbitMQContainer(DockerImageName.parse(RABBIT_IMAGE));

        ELASTICSEARCH = new ElasticsearchContainer(
                DockerImageName.parse(ES_IMAGE)
                        .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                // 堆内存与 compose 一致：单节点演示环境无需 1G，本机还需运行其他容器
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                // 关闭安全认证，与 compose 一致；开启时应用无法连接（未配置凭据）
                .withEnv("xpack.security.enabled", "false")
                .withEnv("xpack.security.http.ssl.enabled", "false")
                // ES 从启动到集群可用约 45~60 秒，默认超时容易在边界上失败
                .withStartupTimeout(Duration.ofMinutes(3));

        List.of(MYSQL, REDIS, RABBITMQ, ELASTICSEARCH).forEach(container -> {
            container.start();
            log.info("集成测试容器已就绪: {}", container.getDockerImageName());
        });
        log.info("四个容器启动完成，共耗时 {} ms", System.currentTimeMillis() - start);
    }

    /**
     * 将容器连接信息注入 Spring。
     *
     * <p>使用 {@code @DynamicPropertySource} 显式声明，而不用 Spring Boot 的
     * {@code @ServiceConnection} 自动推导：ES 使用本地自定义镜像，Redis 也没有官方
     * 容器类型，自动推导覆盖不全；且这段映射是「测试连接到哪个实例」的唯一出处，
     * 显式声明便于排查。这些属性优先级最高，会覆盖 application*.yaml 中的值。
     */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTestBase::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        registry.add("spring.elasticsearch.uris",
                () -> "http://" + ELASTICSEARCH.getHost() + ":" + ELASTICSEARCH.getMappedPort(9200));

        // 测试环境中「指标导出」默认为关闭：属性源 test / configurationProperties 将
        // management.defaults.metrics.export.enabled 置为 false，因此上下文中只有一个
        // SimpleMeterRegistry、/actuator/prometheus 返回 404，而用例需要读取它
        // （验证埋点是否记录，见 AiStreamIntegrationTest）。测试框架的这两个属性源
        // 优先级低于 @DynamicPropertySource，在此显式开启即可。
        registry.add("management.defaults.metrics.export.enabled", () -> "true");
    }

    /** JDBC URL：使用容器映射到宿主机的随机端口，参数与 application.yaml 默认值一致 */
    private static String jdbcUrl() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + MYSQL_DATABASE
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&allowMultiQueries=true&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true";
    }

    /**
     * 将 Docker API 版本设置为引擎接受的版本。未设置时将以「Docker 未启动」的形式失败。
     *
     * <p>该失败的表象具有误导性：报错为 {@code Could not find a valid Docker environment}，
     * 所有策略均失败，看起来像 Docker Desktop 未启动；实际 Docker 一直在运行，
     * 换用 CLI 即可正常使用。真实原因是版本：
     * Testcontainers 1.21.3 的 {@code DockerClientProviderStrategy} 在 {@code api.version}
     * 未设置时固定取 {@code RemoteApiVersion.VERSION_1_32}，而 Docker 29 的引擎
     * 最低只接受 1.44，因此每个请求都被返回 400。
     *
     * <p>判定依据（在 {@code //./pipe/docker_engine} 上直接发送原始 HTTP）：
     * {@code GET /v1.32/info → 400 Bad Request}、{@code GET /v1.44/info → 200 OK}。
     * 400 的响应体仍是 info 结构的空数据，更不易识别为版本问题。
     *
     * <p>1.44 对应 Docker 25+。引擎更旧时（可用 {@code docker version} 查看 API 版本），
     * 通过环境变量 {@code IT_DOCKER_API_VERSION} 指定为其支持的上限。
     */
    private static void tuneDockerApiVersion() {
        if (System.getProperty("api.version") == null) {
            System.setProperty("api.version",
                    System.getenv().getOrDefault("IT_DOCKER_API_VERSION", "1.44"));
        }
    }

    /**
     * Docker 未启动时给出明确提示，而不是让 Testcontainers 抛出大段连接失败信息。
     *
     * <p>注意：不要关闭该 client。它是 Testcontainers 的共享单例，后续启动容器仍需使用
     * （关闭后容器无法启动，且报错不体现该原因）。
     */
    private static void assertDockerAvailable() {
        try {
            DockerClientFactory.instance().client().pingCmd().exec();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "集成测试需要 Docker：请先确认 Docker Desktop 已经启动（" + e.getMessage() + "）", e);
        }
    }

    /**
     * 本地不存在该镜像时提前失败，并给出可直接执行的构建命令。
     * 缺少这层校验时，docker 会抛出更冗长、也更难理解的 pull access denied。
     */
    private static void assertLocalImageExists(String image) {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            throw new IllegalStateException("本地没有镜像 " + image
                    + "。它是 compose 本地构建的（带 IK 中文分词插件），先执行："
                    + " docker compose build elasticsearch", e);
        }
    }

    /**
     * init.sql 位于仓库根目录的 sql/ 下（不在 backend 模块内）。
     * 找不到说明目录结构已变更，或运行测试时的工作目录不是 backend。
     */
    private static void assertInitSqlExists() {
        if (!Files.isReadable(INIT_SQL)) {
            throw new IllegalStateException("找不到建表脚本，期望位置：" + INIT_SQL
                    + "。集成测试要在 backend 目录下运行（mvn test -Pintegration）");
        }
    }

    // ==================== 发请求 / 造数据 ====================

    /**
     * 发送一次真实 HTTP 请求。
     *
     * <p>使用 JDK 的 {@link HttpClient} 而非 {@code TestRestTemplate}/{@code MockMvc}，
     * 因为多个用例的关键在于请求头本身，例如「只接受 {@code text/event-stream}」的场景；
     * 封装好的客户端难以确定实际发送的内容，请求头需逐字节可控，结论才成立。
     *
     * @param headers 自定义请求头（大小写不敏感；未提供 {@code Content-Type} 时按 JSON 携带）
     */
    protected HttpResponse<String> call(String method, String path, String jsonBody, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        boolean hasContentType = headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Content-Type"));
        headers.forEach(builder::header);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            if (!hasContentType) {
                builder.header("Content-Type", "application/json; charset=utf-8");
            }
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 登录并返回 token。演示账号 admin / user 由 DataInitializer 在启动时写入 */
    protected String login(String identifier, String password) throws IOException, InterruptedException {
        HttpResponse<String> resp = call("POST", "/auth/login",
                "{\"identifier\":\"" + identifier + "\",\"password\":\"" + password + "\"}", Map.of());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("登录失败（" + resp.statusCode() + "）：" + resp.body());
        }
        JsonNode token = JSON.readTree(resp.body()).path("data").path("token");
        if (token.isMissingNode() || token.asText().isBlank()) {
            throw new IllegalStateException("登录返回里没有 token：" + resp.body());
        }
        return token.asText();
    }

    /** 未登录请求头：用于覆盖「未登录」路径，不带 Authorization 头 */
    protected Map<String, String> guest() {
        return Map.of();
    }

    /** 带登录态的请求头 */
    protected Map<String, String> asUser(String token) {
        return Map.of("Authorization", token);
    }

    /**
     * 每个用例执行完成后清除限流计数。
     *
     * <p>放在底座而非各测试类中的原因：限流计数存于 Redis，而 Redis 容器在整个 JVM 内共用，
     * 因此它天然是跨用例、跨测试类的全局状态。不清理会持续累加，而多数集成测试类都要在
     * {@code @BeforeEach} 中登录一次，累加超过 {@code /auth/login} 的限额后，
     * 后续测试类将整批拿到 429，报错为「登录失败（429）」，不易识别为限流问题
     * （曾出现一个类 9 个用例全部 ERROR）。
     *
     * <p>这不违反「用例之间顺序无关」：限流用例自身在单个方法内连续发起请求，
     * 清理只发生在方法结束之后。
     */
    @AfterEach
    void clearRateLimitCounters() {
        Set<String> keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /**
     * 等待条件成立，超时则失败。
     *
     * <p>集成测试中无法避免等待：MQ 消息由独立线程在事务提交后投递，ES 为近实时
     * （写入后需等到 refresh 才可检索）。这类等待应封装为方法，而不是 {@code Thread.sleep(3000)}：
     * sleep 过短会偶发失败，过长则每次都是无效等待，且失败时无法判断在等待什么。
     */
    protected static void waitUntil(Duration timeout, String what, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断：" + what, e);
            }
        }
        Assertions.fail("等待超时（" + timeout.toSeconds() + " 秒）：" + what);
    }
}
