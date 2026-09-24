package com.ainovel.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * actuator 鉴权闸门。
 *
 * <p>该类存在的原因是曾出现一处静默失效：actuator 端点不经过 MVC 拦截器
 * （由 Boot 的 {@code WebMvcEndpointHandlerMapping} 提供，从不设置 interceptors），
 * 因此在 SaTokenConfig 中配了白名单并不能保护它，且不产生任何报错，
 * 只有用无效 token 请求一次、看到 200 才会发现。
 *
 * <p>其中最关键的一条判据是 {@link #onlyHealthAndInfoAreAnonymous()}：
 * 口径必须是默认拒绝，因为「白名单列出要保护哪些端点」的写法会在将来向 include
 * 中添加端点时静默遗漏。
 */
class ActuatorGuardFilterTest {

    private final ActuatorGuardFilter filter = new ActuatorGuardFilter();

    private MockHttpServletRequest request(String uri, String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        req.setRemoteAddr("10.0.0.9");
        if (token != null) {
            req.addHeader("Authorization", token);
        }
        return req;
    }

    @Test
    @DisplayName("health / info 匿名放行 —— 容器探活与健康检查要用，内容只有一个状态枚举")
    void healthAndInfoAreAnonymous() throws Exception {
        for (String uri : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/info"}) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request(uri, null), response, chain);

            verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            assertEquals(200, response.getStatus(), uri + " 应当匿名放行");
        }
    }

    @Test
    @DisplayName("prometheus 没有 token → 401，而且不往下走（不是「拦了一半」）")
    void prometheusWithoutTokenIsRejected() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/actuator/prometheus", null), response, chain);

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertTrue(response.getContentAsString().contains("401"), response.getContentAsString());
    }

    @Test
    @DisplayName("管理员 token → 放行")
    void adminTokenAllowed() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getLoginIdByToken("admin-token")).thenReturn(1L);
            stp.when(() -> StpUtil.hasRole(1L, "admin")).thenReturn(true);

            filter.doFilter(request("/actuator/prometheus", "admin-token"), response, chain);
        }

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("登录了但不是管理员 → 401（登录 ≠ 有权看内部指标）")
    void loggedInButNotAdminIsRejected() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getLoginIdByToken("user-token")).thenReturn(2L);
            stp.when(() -> StpUtil.hasRole(2L, "admin")).thenReturn(false);

            filter.doFilter(request("/actuator/metrics", "user-token"), response, chain);
        }

        assertEquals(401, response.getStatus());
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("只有 health / info 匿名 —— 将来往 include 里加端点，默认就是拒绝，不用回来改这里")
    void onlyHealthAndInfoAreAnonymous() {
        assertEquals(java.util.Set.of("health", "info"), ActuatorGuardFilter.ANONYMOUS_ENDPOINTS,
                "放行清单只允许这两个。加别的一律要管理员 —— "
                        + "「列出要保护哪些端点」那种写法会在新增端点时静默漏掉");
    }

    @Test
    @DisplayName("非 actuator 路径一律直接放行（这个过滤器挂在 /* 上）")
    void otherPathsAreUntouched() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("/api/novel/1", null), response, chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("鉴权查询抛异常 → 按未授权处理（fail-closed，与限流相反）")
    void authQueryFailureIsFailClosed() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getLoginIdByToken("t"))
                    .thenThrow(new IllegalStateException("token 仓库连不上"));

            filter.doFilter(request("/actuator/metrics", "t"), response, chain);
        }

        assertEquals(401, response.getStatus(), "查不通就得拦住：放行的代价是把内部指标漏出去");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
