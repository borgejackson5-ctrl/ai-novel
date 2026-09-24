package com.ainovel.config;

import com.ainovel.common.ratelimit.RateLimit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接口级限流的守门测试。
 *
 * <p>限流最常见的问题不是限额不准，而是遗漏：新增接口时忘记加注解，运行正常、日志干净，
 * 但该入口始终未受保护。修改注解限额也存在同类问题：同一限流点两处写入不同的 limit，
 * 结果由先到达的规则决定，表现为限额不稳定。
 *
 * <p>因此本测试约束三件事：
 * <ol>
 *   <li>每个 {@code @RateLimit} 的参数都合法（限额与窗口为正、名字非空）；</li>
 *   <li>同一个限流点（同 name）在所有接口上的限额完全一致：它共享一个计数器，
 *       判据不一致意味着行为取决于哪个接口先被调用；</li>
 *   <li>游客可读的接口必须带注解。这条最关键：游客态是后期开放的能力，
 *       白名单在 {@link SaTokenConfig#GUEST_READABLE}，新增一条白名单规则时容易忘记
 *       同时给对应接口加限流，结果是开放了一个匿名可压的入口。</li>
 * </ol>
 *
 * <p>判据尽量从代码中读取，而不是维护一份平行清单：白名单通过 {@link SaTokenConfig#isGuestReadable}
 * 反查，路径从 controller 的注解拼接。测试中的样例 URI 是探针（用于把正则与具体方法对应起来），
 * 每个探针都要先通过白名单校验；白名单变更而探针过期时，第一步即失败。
 */
@DisplayName("接口级限流：参数、口径一致性、覆盖范围")
class RateLimitGuardTest {

    /** 一个 controller 方法：HTTP 方法 + 路径模板 + 其上的 @RateLimit（可能为 null） */
    private record Endpoint(String httpMethod, String template, Method handler, RateLimit rateLimit) {

        /** 路径模板转正则：{id} 这类占位符匹配一个路径段 */
        Pattern pattern() {
            return Pattern.compile("^" + template.replaceAll("\\{[^}]+}", "[^/]+") + "$");
        }

        boolean matches(String httpMethod, String uri) {
            return this.httpMethod.equals(httpMethod) && pattern().matcher(uri).matches();
        }

        String key() {
            return httpMethod + " " + template;
        }
    }

    /**
     * 白名单的探针：每个 URI 都必须能被 {@link SaTokenConfig#isGuestReadable} 放行
     * （本类第一条测试会复核）。用它把白名单正则与 controller 上的方法对应起来，
     * 否则只能靠人工核对正则能否匹配到某个路径。
     */
    private static final List<String[]> GUEST_PROBES = List.of(
            new String[]{"GET", "/novel/123"},
            new String[]{"GET", "/novel/page"},
            new String[]{"GET", "/novel/search"},
            new String[]{"POST", "/novel/search/smart"},
            new String[]{"GET", "/novel/by-author/9"},
            new String[]{"GET", "/category/list"},
            new String[]{"GET", "/chapter/789"},
            new String[]{"GET", "/chapter/page/123"},
            new String[]{"GET", "/chapter/456/content"},
            new String[]{"GET", "/comment/page/123"},
            new String[]{"GET", "/comment/chapter/456"},
            new String[]{"GET", "/comment/replies/789"},
            new String[]{"GET", "/rank/hot"}
    );

    /**
     * 已知豁免：确实有限流，但走 service 层而非注解。
     *
     * <p>榜单的限额需要能通过 {@code novel.rank-rate-limit} 调整（它是最容易被刷的读接口），
     * 而注解参数是编译期常量，因此该处保留了 service 层的 {@code FixedWindowRateLimiter} 调用
     * （与注解共用同一个限流组件，不是第二份 Lua）。新增豁免必须写在这里，且确实在 service 层
     * 调用了限流器：这份清单是人工承诺，其价值在于新接口若要绕过注解，必须显式修改本文件，
     * 而不是静默遗漏。
     */
    private static final List<String> NOT_ANNOTATED_BY_DESIGN = List.of(
            "GET /rank/hot",
            "GET /rank/{type}"
    );

    /** 必须限流的写接口：防批量注册小号、防撞库、防刷评论。这里用路径模板精确定位 */
    private static final List<String> MUST_BE_RATE_LIMITED = List.of(
            "POST /auth/code",
            "POST /auth/login",
            "POST /auth/register",
            "POST /auth/reset-password",
            "POST /comment",
            "POST /comment/{id}/like",
            "POST /novel/search/smart",     // 智能搜索会调用模型：未登录也可使用，不限流会产生未受控的模型开销
            // 高频写：正常用户调用也频繁，但脚本可将其刷成写库风暴
            "POST /history/record",
            "PUT /reader/progress",
            "POST /bookshelf/{novelId}",
            "DELETE /bookshelf/{novelId}"
    );

    private static final List<Endpoint> ENDPOINTS = new ArrayList<>();

    @BeforeAll
    static void scanEndpoints() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.ainovel.module")) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            String base = firstPath(clazz.getAnnotation(RequestMapping.class));
            for (Method method : clazz.getDeclaredMethods()) {
                for (Map.Entry<String, String> e : mappingsOf(method).entrySet()) {
                    ENDPOINTS.add(new Endpoint(e.getKey(), base + e.getValue(),
                            method, method.getAnnotation(RateLimit.class)));
                }
            }
        }
        assertThat(ENDPOINTS).as("没扫到任何 controller —— 包名或注解过滤器改了?").isNotEmpty();
    }

    @Test
    @DisplayName("探针有效：每个游客样例 URI 都真的被白名单放行")
    void guestProbesStillValid() {
        for (String[] probe : GUEST_PROBES) {
            assertThat(SaTokenConfig.isGuestReadable(probe[0], probe[1]))
                    .as("探针 %s %s 已不在游客白名单里 —— 白名单改了，请同步更新本测试的探针",
                            probe[0], probe[1])
                    .isTrue();
        }
    }

    @Test
    @DisplayName("注解参数合法：限额与窗口为正、名字非空")
    void parametersAreSane() {
        List<Endpoint> annotated = ENDPOINTS.stream().filter(e -> e.rateLimit() != null).toList();
        assertThat(annotated).as("一个 @RateLimit 都没扫到 —— 注解是不是被删了?").isNotEmpty();

        for (Endpoint e : annotated) {
            RateLimit r = e.rateLimit();
            assertThat(r.name()).as("%s 的限流点名字", e.key()).isNotBlank();
            assertThat(r.limit()).as("%s 的限额必须为正数", e.key()).isPositive();
            assertThat(r.window()).as("%s 的窗口必须为正数", e.key()).isPositive();
        }
    }

    @Test
    @DisplayName("同一限流点的限额必须一致 —— 否则行为取决于哪个接口先被调用")
    void sameNameMeansSameQuota() {
        Map<String, String> quotaByName = new HashMap<>();
        Map<String, String> firstSeen = new HashMap<>();

        for (Endpoint e : ENDPOINTS) {
            if (e.rateLimit() == null) {
                continue;
            }
            RateLimit r = e.rateLimit();
            String quota = r.limit() + "/" + r.window() + "s";
            String previous = quotaByName.putIfAbsent(r.name(), quota);
            assertThat(previous == null || previous.equals(quota))
                    .as("限流点 %s 在 %s 上是 %s，在 %s 上是 %s —— 两者共用一个计数器，判据必须一致",
                            r.name(), firstSeen.get(r.name()), previous, e.key(), quota)
                    .isTrue();
            firstSeen.putIfAbsent(r.name(), e.key());
        }
    }

    @Test
    @DisplayName("游客可读的接口必须限流 —— 匿名能打的接口是最容易被压的")
    void guestReadableEndpointsAreRateLimited() {
        for (String[] probe : GUEST_PROBES) {
            List<Endpoint> hit = ENDPOINTS.stream().filter(e -> e.matches(probe[0], probe[1])).toList();
            assertThat(hit).as("探针 %s %s 没有对应的 controller 方法 —— 接口改路径了?", probe[0], probe[1])
                    .isNotEmpty();
            for (Endpoint e : hit) {
                if (NOT_ANNOTATED_BY_DESIGN.contains(e.key())) {
                    continue;
                }
                assertThat(e.rateLimit())
                        .as("游客可读的 %s 没有 @RateLimit —— 它在白名单里，匿名就能打，不限流等于开了个口子", e.key())
                        .isNotNull();
            }
        }
    }

    @Test
    @DisplayName("敏感写接口必须限流 —— 防批量注册小号 / 撞库 / 刷评论")
    void sensitiveWriteEndpointsAreRateLimited() {
        for (String must : MUST_BE_RATE_LIMITED) {
            Endpoint e = ENDPOINTS.stream().filter(x -> x.key().equals(must)).findFirst()
                    .orElseThrow(() -> new AssertionError("清单里的 " + must + " 没有对应的 controller 方法"));
            assertThat(e.rateLimit()).as("%s 必须限流", must).isNotNull();
        }
    }

    @Test
    @DisplayName("豁免清单没有过期项 —— 原样保留会掩盖「这个接口其实已经改了」")
    void exemptionsAreStillReal() {
        for (String exempt : NOT_ANNOTATED_BY_DESIGN) {
            assertThat(ENDPOINTS).as("豁免项 %s 已经不存在了，请从豁免清单删掉", exempt)
                    .anyMatch(e -> e.key().equals(exempt));
        }
    }

    /** 类级 @RequestMapping 的第一个路径（没有就是空串） */
    private static String firstPath(RequestMapping mapping) {
        if (mapping == null) {
            return "";
        }
        String[] paths = mapping.value().length > 0 ? mapping.value() : mapping.path();
        return paths.length > 0 ? paths[0] : "";
    }

    /** 方法上的映射注解 → (HTTP 方法, 子路径)。同一个方法可能声明多个路径 */
    private static Map<String, String> mappingsOf(Method method) {
        Map<String, String> result = new LinkedHashMap<>();
        addAll(result, "GET", pathsOf(method.getAnnotation(GetMapping.class)));
        addAll(result, "POST", pathsOf(method.getAnnotation(PostMapping.class)));
        addAll(result, "PUT", pathsOf(method.getAnnotation(PutMapping.class)));
        addAll(result, "DELETE", pathsOf(method.getAnnotation(DeleteMapping.class)));
        addAll(result, "PATCH", pathsOf(method.getAnnotation(PatchMapping.class)));
        return result;
    }

    private static String[] pathsOf(GetMapping a) {
        return a == null ? null : (a.value().length > 0 ? a.value() : a.path());
    }

    private static String[] pathsOf(PostMapping a) {
        return a == null ? null : (a.value().length > 0 ? a.value() : a.path());
    }

    private static String[] pathsOf(PutMapping a) {
        return a == null ? null : (a.value().length > 0 ? a.value() : a.path());
    }

    private static String[] pathsOf(DeleteMapping a) {
        return a == null ? null : (a.value().length > 0 ? a.value() : a.path());
    }

    private static String[] pathsOf(PatchMapping a) {
        return a == null ? null : (a.value().length > 0 ? a.value() : a.path());
    }

    private static void addAll(Map<String, String> target, String httpMethod, String[] paths) {
        if (paths == null) {
            return;
        }
        if (paths.length == 0) {
            // 无路径参数的 @PostMapping：路径即类级路径
            target.putIfAbsent(httpMethod, "");
            return;
        }
        for (String p : paths) {
            target.putIfAbsent(httpMethod, p);
        }
    }
}
