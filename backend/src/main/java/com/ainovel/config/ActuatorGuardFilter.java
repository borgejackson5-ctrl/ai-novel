package com.ainovel.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 为 actuator 端点增加鉴权校验。
 *
 * <p>不使用拦截器的原因：actuator 端点由 Boot 管理的
 * {@code WebMvcEndpointHandlerMapping} 提供，该 mapping 不调用 setInterceptors，
 * 而 {@code WebMvcConfigurer.addInterceptors} 仅作用于普通请求映射与资源映射。
 * 因此 {@code SaTokenConfig} 中的拦截器对 {@code /actuator/**} 完全不生效。
 * 验证结果：携带无效 token 请求 {@code /actuator/prometheus} 仍返回 200，
 * 而 Sa-Token 本应抛出 {@code InvalidTokenException}。过滤器则不同：
 * 其在 DispatcherServlet 之前执行，任何 HandlerMapping 都无法绕过。
 *
 * <p>策略为默认拒绝：仅 {@code health} 与 {@code info} 匿名放行
 * （供容器探活与健康检查使用，内容仅包含状态枚举与构建信息），
 * 其余端点一律要求管理员权限。默认拒绝的优势在于后续向 include 中新增端点时无需修改此处；
 * 反向的白名单写法（列出需保护的端点）容易遗漏。
 *
 * <p>该风险的实际边界如下：公网部署下无法触及，
 * nginx 仅反代 {@code /api/}（不包含 actuator），后端容器也未映射端口。
 * 实际暴露面为本机开发实例（8081）与同一容器网络内。因此该过滤器的意义在于
 * 将安全约束内置于应用，而非依赖某次部署的端口配置恰好正确，更换部署方式时不会静默失效。
 *
 * <p>查询失败即拒绝（fail-closed），与限流策略相反：放行的代价是泄漏内部指标，
 * 拦截的代价仅为管理员暂时无法查看。
 */
@Slf4j
@Component
public class ActuatorGuardFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PREFIX = "/actuator";

    /** 匿名放行的端点（健康检查 / 构建信息，不含敏感内容） */
    static final Set<String> ANONYMOUS_ENDPOINTS = Set.of("health", "info");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(ACTUATOR_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if (ANONYMOUS_ENDPOINTS.contains(endpointOf(path)) || isAdmin(request)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("actuator 端点未授权访问已拒绝: path={}, remote={}", path, request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"未登录或无权访问\"}");
    }

    /** {@code /actuator/prometheus} → {@code prometheus}；{@code /actuator/health/liveness} → {@code health} */
    private String endpointOf(String path) {
        String rest = path.substring(ACTUATOR_PREFIX.length());
        while (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        int slash = rest.indexOf('/');
        return (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
    }

    /**
     * 使用 token 直接查询登录态与角色。
     *
     * <p>此处不能使用 {@code StpUtil.isLogin()} 等依赖当前请求上下文的写法：
     * 过滤器在 DispatcherServlet 之前执行，此时 Sa-Token 的 Spring 上下文尚未挂载。
     * {@code getLoginIdByToken} / {@code hasRole(loginId, role)} 为两个静态重载，
     * 仅访问 token 仓库与 {@code StpInterfaceImpl}，不依赖请求上下文。
     */
    private boolean isAdmin(HttpServletRequest request) {
        try {
            String token = request.getHeader("Authorization");
            if (token == null || token.isBlank()) {
                return false;
            }
            Object loginId = StpUtil.getLoginIdByToken(token);
            return loginId != null && StpUtil.hasRole(loginId, "admin");
        } catch (Exception e) {
            // fail-closed：查询失败按无权限处理，不开放指标访问
            log.debug("actuator 鉴权查询失败，按未授权处理: {}", e.getMessage());
            return false;
        }
    }
}
