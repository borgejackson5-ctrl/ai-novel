package com.ainovel.common.idempotent;

import com.ainovel.common.annotation.Idempotent;
import com.ainovel.common.domain.ResponseDTO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等键的守门测试。
 *
 * <p>该机制用错位置比不用更糟：
 * <ul>
 *   <li>标在读接口上 ⇒ 重放会返回旧数据，而调用方以为拿到的是最新的；</li>
 *   <li>标在返回类型不是 {@code ResponseDTO} 的方法上 ⇒ 重放时按 {@code ResponseDTO} 反序列化，
 *       得到空对象并当作成功返回：一个「成功了但什么都没发生」的响应，比报错难查得多。</li>
 * </ul>
 * 这两种情况都不会在运行时报错，只能靠守门测试约束。
 *
 * <p>另外约束一份「必须标」的清单：花钱的三个接口（下单 / 支付 / 解锁）与关单。
 * 新增支付类接口时，这份清单会提示「该接口是否需要幂等」。它的价值不是自动发现，
 * 而是要求必须显式做一次判断。
 */
@DisplayName("幂等键：标注位置与覆盖范围")
class IdempotentGuardTest {

    private static final String[] WRITE_ANNOTATIONS = {"POST", "PUT", "DELETE", "PATCH"};

    /** 这些接口涉及资金，必须接受幂等键（标识使用 类#方法，比路径更稳定） */
    private static final List<String> MUST_BE_IDEMPOTENT = List.of(
            "CoinController#recharge",
            "CoinController#mockPay",
            "CoinController#cancel",
            "SubscribeController#unlock"
    );

    private record Annotated(String label, Method method, String httpMethods) {
    }

    private static final List<Method> ALL_CONTROLLER_METHODS = new ArrayList<>();
    private static final List<Annotated> ANNOTATED = new ArrayList<>();

    @BeforeAll
    static void scan() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.ainovel.module")) {
            Class<?> clazz = Class.forName(bd.getBeanClassName());
            for (Method method : clazz.getDeclaredMethods()) {
                ALL_CONTROLLER_METHODS.add(method);
                if (method.getAnnotation(Idempotent.class) != null) {
                    ANNOTATED.add(new Annotated(
                            clazz.getSimpleName() + "#" + method.getName(), method, httpMethodsOf(method)));
                }
            }
        }
        assertThat(ALL_CONTROLLER_METHODS).as("没扫到 controller —— 包名或过滤器改了?").isNotEmpty();
    }

    @Test
    @DisplayName("@Idempotent 只出现在写接口上（读接口缓存结果 = 重放会返回旧数据）")
    void onlyOnWriteEndpoints() {
        assertThat(ANNOTATED).as("一个都没标 —— 注解是不是被删了?").isNotEmpty();

        for (Annotated a : ANNOTATED) {
            assertThat(a.httpMethods())
                    .as("%s 标了 @Idempotent，但它不是写接口（%s）—— 读接口被重放会返回旧数据",
                            a.label(), a.httpMethods().isEmpty() ? "没有任何映射注解" : a.httpMethods())
                    .isIn(WRITE_ANNOTATIONS);
        }
    }

    @Test
    @DisplayName("标注的方法返回 ResponseDTO —— 否则重放会反序列化失败，回一个空响应")
    void returnsResponseDto() {
        for (Annotated a : ANNOTATED) {
            Class<?> returnType = a.method().getReturnType();
            assertThat(ResponseDTO.class.isAssignableFrom(returnType))
                    .as("%s 返回的是 %s，但重放逻辑按 ResponseDTO 反序列化 —— 换个位置标，或者先让它返回 ResponseDTO",
                            a.label(), returnType.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("碰钱的接口必须接受幂等键")
    void moneyEndpointsAreIdempotent() {
        List<String> labels = ANNOTATED.stream().map(Annotated::label).toList();
        for (String must : MUST_BE_IDEMPOTENT) {
            assertThat(labels)
                    .as("%s 会改余额/订单，必须能被幂等键保护（或明确说明为什么不）", must)
                    .contains(must);
        }
    }

    @Test
    @DisplayName("清单里的接口仍然存在 —— 方法改名后清单会变成一句空话")
    void listedEndpointsStillExist() {
        List<String> existing = ALL_CONTROLLER_METHODS.stream()
                .map(m -> m.getDeclaringClass().getSimpleName() + "#" + m.getName())
                .toList();
        for (String must : MUST_BE_IDEMPOTENT) {
            assertThat(existing).as("清单里的 %s 已经不存在了，请更新清单", must).contains(must);
        }
    }

    /** 该方法上声明的 HTTP 方法（只识别这四种写操作） */
    private static String httpMethodsOf(Method method) {
        if (method.getAnnotation(PostMapping.class) != null) {
            return "POST";
        }
        if (method.getAnnotation(PutMapping.class) != null) {
            return "PUT";
        }
        if (method.getAnnotation(DeleteMapping.class) != null) {
            return "DELETE";
        }
        if (method.getAnnotation(PatchMapping.class) != null) {
            return "PATCH";
        }
        return "";
    }
}
