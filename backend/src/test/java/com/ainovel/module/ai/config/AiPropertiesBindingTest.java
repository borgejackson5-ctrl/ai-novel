package com.ainovel.module.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 配置绑定测试：约束「application.yaml 中的 ai.* 必须真的绑定到 AiProperties」。
 *
 * <p>背景：{@code ai.timeout} / {@code ai.max-tokens} / {@code ai.enable-log} 三个 key 曾出现
 * 「已配置但未生效」：{@code AiProperties} 中没有对应字段（或字段名不匹配），
 * Spring 会静默忽略，既不报错也不提示。表现为「改了配置没反应」，
 * 并且会导致请求体中缺少 max_tokens、模型输出长度不受控这类产生费用的问题。
 *
 * <p>这里加载真实的 application.yaml（而不是在测试中另抄一份 key），
 * 因此 yaml 与字段名任一侧写错都会失败。
 */
class AiPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BindingEnabledConfig.class);

    /**
     * 仅把 {@code @Component} 类放入 ApplicationContextRunner 不会装配
     * {@code ConfigurationPropertiesBindingPostProcessor}：那样字段永远停在类中的默认值，
     * 断言写多少都「通过」。必须显式使用 {@code @EnableConfigurationProperties}。
     * （首次编写时断言值 60/5/2000 恰好等于字段默认值，单看无法区分「已绑定」与「未绑定」。）
     */
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(AiProperties.class)
    static class BindingEnabledConfig {
    }

    @Test
    @DisplayName("application.yaml 的 ai.* 全部绑上，关键项不为空")
    void yamlKeysAreBound() {
        runner.run(ctx -> {
            assertNotNull(ctx.getBean(AiProperties.class), "AiProperties 没注册");
            AiProperties props = ctx.getBean(AiProperties.class);

            assertNotNull(props.getBaseUrl(), "ai.base-url");
            assertNotNull(props.getModel(), "ai.model");
            assertNotNull(props.getTemperature(), "ai.temperature");
            // 下面四项曾为死配置：断言具体值即断言 yaml 的约定（修改 yaml 时同步修改此处）
            assertEquals(60, props.getTimeoutSeconds(), "ai.timeout-seconds 没绑上（单位是秒）");
            assertEquals(5, props.getSearchTimeoutSeconds(), "ai.search-timeout-seconds 没绑上");
            assertEquals(2000, props.getMaxTokens(), "ai.max-tokens 没绑上 —— 请求体就不会带 max_tokens");
            assertTrue(props.getEnableLog(), "ai.enable-log 没绑上");
            assertTrue(props.getMockEnabled(), "ai.mock-enabled 没绑上");
            // 工具循环的两道上限：未绑定即等于没有上限（模型持续请求工具时请求会阻塞在 HTTP 线程上）
            assertEquals(5, props.getReviewMaxToolRounds(), "ai.review-max-tool-rounds 没绑上");
            assertEquals(120, props.getReviewBudgetSeconds(), "ai.review-budget-seconds 没绑上");
        });
    }

    @Test
    @DisplayName("绑定管线是活的：覆盖一个 key 必须真的改到字段（否则断言只落在字段默认值上）")
    void bindingPipelineIsLive() {
        // 上面那组断言中的 60 / 5 / 2000 与 AiProperties 的字段默认值完全相同，
        // 单看它们无法区分「yaml 确实绑上了」与「后处理器未装配、使用的就是字段默认值」。
        // 这里设置一个与默认值不同的覆盖值：未绑定时仍为 60，测试立即失败。
        runner.withPropertyValues("ai.timeout-seconds=99").run(ctx -> {
            assertEquals(99, ctx.getBean(AiProperties.class).getTimeoutSeconds(),
                    "覆盖值没生效 —— 说明 @ConfigurationProperties 绑定后处理器没被装配，"
                            + "本测试类其它断言都是假的");
        });
    }

    @Test
    @DisplayName("反向检查：yaml 里每个 ai.* 都要有对应字段（防再次出现死配置）")
    void everyYamlAiKeyHasField() {
        runner.run(ctx -> {
            Set<String> keys = new HashSet<>();
            for (PropertySource<?> ps : ctx.getEnvironment().getPropertySources()) {
                if (ps instanceof EnumerablePropertySource<?> eps) {
                    for (String name : eps.getPropertyNames()) {
                        if (name.startsWith("ai.")) {
                            keys.add(name);
                        }
                    }
                }
            }
            assertTrue(keys.size() >= 8, "只扫到 " + keys.size() + " 个 ai.* 配置，yaml 是不是没被加载？");

            for (String key : keys) {
                String field = kebabToCamel(key.substring("ai.".length()));
                boolean exists = false;
                for (java.lang.reflect.Field f : AiProperties.class.getDeclaredFields()) {
                    if (f.getName().equals(field)) {
                        exists = true;
                        break;
                    }
                }
                assertTrue(exists, "配置 " + key + " 在 AiProperties 里找不到字段 " + field
                        + " —— 这种「死配置」Spring 会静默忽略，改了也没用");
            }
        });
    }

    private static String kebabToCamel(String kebab) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : kebab.toCharArray()) {
            if (c == '-') {
                upper = true;
                continue;
            }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }
}
