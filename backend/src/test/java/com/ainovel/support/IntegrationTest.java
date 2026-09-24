package com.ainovel.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试的入口注解：将「需要真实中间件」与「默认不应执行」两件事绑定。
 *
 * <p>做成组合注解、而不要求每个测试类自行标注 {@code @Tag("integration")} 的原因：
 * 后者难免遗漏，遗漏后该测试会混入日常的 {@code mvn test}，
 * 使该命令依赖 Docker、变慢，并在未启动 Docker 的机器上大量失败。
 * 合并为一个注解后，「漏标 tag」与「漏写 {@code @SpringBootTest}」成为同一个错误，
 * 后者会使测试无法运行，不会静默混入。
 *
 * <p>使用真实 HTTP 端口（{@code RANDOM_PORT}）而非 MockMvc：需要验证的问题中，
 * 异常处理器的内容协商、Sa-Token 拦截器、SSE 的 ASYNC 分发都发生在 servlet 容器层，
 * MockMvc 会绕过其中一部分。代价是每次运行需启动一个 Tomcat，
 * 但上下文在所有集成测试之间复用（注解完全一致 → 同一个 context 缓存 key）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public @interface IntegrationTest {
}
