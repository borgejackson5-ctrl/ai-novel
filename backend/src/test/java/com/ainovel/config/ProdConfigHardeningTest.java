package com.ainovel.config;

import com.ainovel.common.client.DashScopeProperties;
import com.ainovel.module.oss.config.OssProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产加固配置的守门测试：断言 {@code application-prod.yaml} 中各项关闭开发者门面的设置
 * 确实被解析出来。
 *
 * <p>单独编写该测试的原因：
 * <ul>
 *   <li>该文件曾被 {@code .gitignore} 忽略，新 clone 的仓库中不存在它，
 *       而 Spring Boot 缺少 profile 配置不会报错，只会让这些加固静默失效；</li>
 *   <li>配置「已写但未生效」是本项目反复出现的问题（{@code ai.max-tokens} 等三个死配置）。
 *       本次实例：{@code knife4j.production: true} 看似「生产屏蔽」，实际是死配置。
 *       knife4j 的自动配置类级上是 {@code @ConditionalOnProperty("knife4j.enable")}，
 *       enable=false 时该屏蔽过滤器不会被创建（已反编译确认，且 /doc.html 仍返回 200）。
 *       真正有效的开关是 {@code spring.web.resources.add-mappings: false}。</li>
 * </ul>
 *
 * <p>加载的是真实 yaml，而不是在测试中另抄一份 key；最后一个用例是对照组，
 * 用「不带 prod profile 时这些键不存在」证明前面的断言确实来自该文件。
 */
class ProdConfigHardeningTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("prod：接口文档 / knife4j UI / Druid 监控台 / SQL 日志 全关，且支付密钥不落盘")
    void productionHardeningIsInEffect() {
        runner.withPropertyValues("spring.profiles.active=prod").run(ctx -> {
            Environment env = ctx.getEnvironment();
            assertTrue(env.matchesProfiles("prod"), "prod profile 没激活，用例前提就不成立");

            assertEquals("false", env.getProperty("springdoc.api-docs.enabled"),
                    "接口文档必须关（否则生产上 /v3/api-docs 直接暴露全部接口）");
            assertEquals("false", env.getProperty("springdoc.swagger-ui.enabled"),
                    "swagger-ui 必须关");
            assertEquals("false", env.getProperty("spring.web.resources.add-mappings"),
                    "静态资源映射必须关 —— 这是唯一能屏蔽 knife4j UI（/doc.html）的开关，"
                            + "见 application-prod.yaml 里那段说明");
            assertEquals("false", env.getProperty("spring.datasource.druid.stat-view-servlet.enabled"),
                    "Druid 监控台必须关（它是不需要登录就能看的 SQL 与连接池信息）");
            assertTrue(String.valueOf(env.getProperty("mybatis-plus.configuration.log-impl"))
                            .endsWith("NoLoggingImpl"),
                    "生产必须关 SQL 日志");
            // 反向约束：knife4j 自身的 production 开关在本项目中不会生效，
            // 若有人写回 true，说明又回到「配置未生效」的情况
            assertNotEquals("true", env.getProperty("knife4j.production"),
                    "knife4j.production 在 enable=false 时是死配置（已反编译确认类是 @ConditionalOnProperty"
                            + "(\"knife4j.enable\")），要屏蔽 UI 请用 spring.web.resources.add-mappings=false");

            String notifySecret = env.getProperty("pay.notify-secret");
            assertTrue(notifySecret == null || notifySecret.isBlank(),
                    "支付回调密钥只能由环境变量注入，yaml 里不许出现明文，实际=" + notifySecret);
        });
    }

    @Test
    @DisplayName("对照组：不激活 prod 时这些键不存在 —— 证明断言来自 application-prod.yaml")
    void withoutProdProfileTheKeysAreAbsent() {
        runner.run(ctx -> {
            Environment env = ctx.getEnvironment();
            assertNotEquals("true", env.getProperty("knife4j.production"),
                    "没激活 prod 却是 true，说明这些值来自别处，本测试就守不住 prod 文件了");
            assertNotEquals("false", env.getProperty("springdoc.api-docs.enabled"),
                    "本地开发要能看接口文档，不该被 prod 的开关污染");
            assertNotEquals("false", env.getProperty("spring.web.resources.add-mappings"),
                    "本地要能打开 knife4j 页面（/doc.html），不能被 prod 关掉");
        });
    }

    /**
     * OSS / 文生图的凭据在生产上如何提供？
     *
     * <p>这两个模块的配置项只写在 gitignored 的 application-local.yaml 中，
     * 而该文件已被 .dockerignore 排除出构建上下文，镜像中不存在它，因此生产必须另寻途径。
     * 途径是 Spring 的宽松绑定：{@code OSS_ACCESS_KEY_ID}、{@code DASHSCOPE_API_KEY}
     * 这类环境变量会直接绑定到 {@code oss.access-key-id} / {@code dashscope.api-key}，
     * 无需在 yaml 中写占位符。
     *
     * <p>验证必须精确到「环境变量」这一来源：{@code OSS_BUCKET → oss.bucket} 的名称映射
     * 只对 {@code SystemEnvironmentPropertySource} 生效，普通属性源（含
     * {@code withSystemProperties}、{@code withPropertyValues}）不做这层映射，
     * 用它们冒充环境变量会得到一个「看似在测环境变量、实际未测」的假测试。
     */
    @Test
    @DisplayName("OSS / 文生图 可用「大写下划线」环境变量直接注入（镜像里没有 local 文件时的唯一途径）")
    void externalKeysCanBeInjectedByEnvStyleNames() {
        StandardEnvironment env = new StandardEnvironment();
        // 置于最前表示最高优先级，模拟真实环境变量（真实进程环境也是 Env 属性源）
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("probe-env", Map.of(
                "OSS_BUCKET", "probe-bucket",
                "OSS_ACCESS_KEY_ID", "probe-ak",
                "DASHSCOPE_MODEL", "probe-model")));

        OssProperties oss = Binder.get(env).bind("oss", Bindable.of(OssProperties.class)).get();
        assertEquals("probe-bucket", oss.getBucket(), "OSS_BUCKET 没能绑到 oss.bucket —— 生产就没法配 OSS");
        assertEquals("probe-ak", oss.getAccessKeyId(), "OSS_ACCESS_KEY_ID 没能绑到 oss.access-key-id");

        DashScopeProperties dashScope = Binder.get(env)
                .bind("dashscope", Bindable.of(DashScopeProperties.class)).get();
        assertEquals("probe-model", dashScope.getModel(), "DASHSCOPE_MODEL 没能绑到 dashscope.model");
    }
}
