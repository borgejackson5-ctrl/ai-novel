package com.ainovel.config;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ainovel.module.ai.controller.AiController;
import com.ainovel.module.search.controller.SearchController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守门测试：运维 / 重建类入口必须限制角色。
 *
 * <p>必要性：项目中的全局拦截器只做 {@code checkLogin}（登录即可通过），
 * 角色由方法上的 {@code @SaCheckRole} 控制。也就是说忘记加注解时接口对任何登录用户开放，
 * 且不产生任何报错。该类问题已出现三次：
 *
 * <ol>
 *   <li>{@code POST /search/reindex}（全量重建索引）；</li>
 *   <li>{@code POST /search/vector-reindex}（重建某本书的章节向量块）：
 *       修复时在 {@code SearchController} 中留有说明，该前缀属于读者侧，缺少角色约束时
 *       任何登录用户都能触发；</li>
 *   <li>{@code POST /ai/audit/{novelId}}：它把任意作品的审核状态改为待审(0)，
 *       而待审不在 {@code NovelVisibility.VISIBLE_AUDIT_STATUSES} 中，
 *       即任何登录用户都能把他人作品从读者视野中移除，并额外消耗一次平台 AI 调用。
 *       前两次修复时未一并检索同类接口，因此补充本守门测试。</li>
 * </ol>
 *
 * <p>使用显式清单而不做全量扫描的原因：判断「该接口是否属于运维入口」是语义判断，
 * 代码中没有可靠的机器判据（按前缀扫描会包含读者接口，按 summary 关键词扫描不够稳定）。
 * 清单的代价是新增运维入口时需把方法名加到此处，这是有意为之：
 * 添加时会被提示核对该接口是否需要限制角色。
 *
 * <p>清单中每个方法都必须满足：① 是写接口（POST/PUT/DELETE）；② 带 {@code @SaCheckRole}，
 * 且角色包含 {@code admin}。
 */
class OpsEndpointRoleGuardTest {

    /** 运维 / 重建类入口：不在正常业务链路上、但会修改数据 */
    private static final List<Endpoint> OPS_ENDPOINTS = List.of(
            new Endpoint(SearchController.class, "reindex", "全量重建搜索索引"),
            new Endpoint(SearchController.class, "vectorReindex", "重建某本作品的章节向量索引"),
            new Endpoint(AiController.class, "submitAudit", "手动重跑 AI 预审（会把作品置为待审）"));

    private record Endpoint(Class<?> controller, String method, String what) {
    }

    @Test
    @DisplayName("运维入口一律 @SaCheckRole(\"admin\")，且只能挂在对外的写接口上")
    void opsEndpointsRequireAdminRole() {
        List<String> problems = new ArrayList<>();

        for (Endpoint endpoint : OPS_ENDPOINTS) {
            String where = endpoint.controller().getSimpleName() + "." + endpoint.method();
            Method method = findMethod(endpoint.controller(), endpoint.method());
            if (method == null) {
                // 方法被改名或删除：清单过期也必须报出，否则该断言会静默失效（始终通过）
                problems.add(where + " 不存在了 —— 清单要跟着改，或者它真的被删了");
                continue;
            }

            if (!isWriteEndpoint(method)) {
                problems.add(where + "（" + endpoint.what() + "）不再是写接口了，请核对清单");
            }

            SaCheckRole role = method.getAnnotation(SaCheckRole.class);
            if (role == null) {
                problems.add(where + "（" + endpoint.what()
                        + "）没有 @SaCheckRole —— 全局拦截器只做 checkLogin，等于任何登录用户都能调用");
            } else if (List.of(role.value()).stream().noneMatch("admin"::equals)) {
                problems.add(where + " 的角色不是 admin，而是 " + List.of(role.value()));
            }
        }

        assertTrue(problems.isEmpty(),
                "运维入口的角色约束缺失：\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("清单本身是活的（空清单会让上面那条测试变成永远绿）")
    void listIsNotEmpty() {
        assertFalse(OPS_ENDPOINTS.isEmpty(), "运维入口清单空了，这条守门测试就没有意义了");
    }

    private boolean isWriteEndpoint(Method method) {
        for (Class<? extends Annotation> type : List.of(PostMapping.class, PutMapping.class, DeleteMapping.class)) {
            if (method.getAnnotation(type) != null) {
                return true;
            }
        }
        return false;
    }

    private Method findMethod(Class<?> controller, String name) {
        for (Method method : controller.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }
}
