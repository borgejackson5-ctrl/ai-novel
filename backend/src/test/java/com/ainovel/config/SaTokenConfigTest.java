package com.ainovel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 游客可读白名单的守门测试。
 *
 * <p>重点不是放行了哪些路径，而是没有误放哪些，尤其是
 * {@code POST /novel/publish}、{@code /novel/save}、{@code /novel/reindex} 这一批：
 * 它们与作品详情 {@code /novel/{id}} 只差一个路径段，若写成 {@code /novel/*}，
 * 会连写接口一起放开。
 */
@DisplayName("游客可读白名单：只放该放的")
class SaTokenConfigTest {

    private static boolean allowed(String method, String uri) {
        return SaTokenConfig.isGuestReadable(method, uri);
    }

    @Test
    @DisplayName("放行的：读者侧只读接口（不注册也能先看内容）")
    void guestReadable() {
        List<String> getUris = List.of(
                "/novel/123",              // 作品详情
                "/novel/page",             // 书库列表
                "/novel/search",           // 全文搜索
                "/novel/by-author/9",      // 某位作者的作品
                "/rank/hot",               // 榜单
                "/rank/new",
                "/category/list",          // 分类
                "/chapter/789",            // 单章元数据（阅读页要先取它）
                "/chapter/page/123",       // 目录
                "/chapter/456/content",    // 正文（付费章挡在 service 层）
                "/comment/page/123",       // 评论列表
                "/comment/chapter/456",
                "/comment/replies/789"
        );
        for (String uri : getUris) {
            assertThat(allowed("GET", uri)).as("GET %s 应放行给游客", uri).isTrue();
        }
        // 智能搜索语义上是只读的，只是接口声明成了 POST
        assertThat(allowed("POST", "/novel/search/smart")).isTrue();
    }

    @Test
    @DisplayName("拦截的：个人数据一律要登录")
    void personalDataNeedsLogin() {
        List<String> uris = List.of(
                "/bookshelf/list", "/bookshelf/123/status",
                "/history/page", "/message/page", "/message/unread-count",
                "/coin/balance", "/coin/orders",
                "/user/me", "/reader/progress", "/reader/preference",
                "/feedback/my",
                "/novel/mine", "/novel/mine/123", "/novel/mine/123/stats",
                "/ai/my-config", "/ai/config",
                "/subscribe/check/123", "/subscribe/unlock-status/123"
        );
        for (String uri : uris) {
            assertThat(allowed("GET", uri)).as("GET %s 含个人数据，必须登录", uri).isFalse();
        }
    }

    @Test
    @DisplayName("拦截的：与详情同层级的写接口（通配符写法会误伤这一批）")
    void sameLevelWritesNeedLogin() {
        List<String> uris = List.of(
                "/novel/publish", "/novel/save", "/novel/reindex",
                "/novel/vector-reindex", "/novel/vector-search", "/novel/import-parse"
        );
        for (String uri : uris) {
            assertThat(allowed("POST", uri))
                    .as("POST %s 是写接口，和 /novel/{id} 只差一个路径段", uri)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("拦截的：其余写操作与 AI 创作")
    void writesAndAiNeedLogin() {
        List<String> posts = List.of(
                "/novel/123/like", "/novel/123/read", "/novel/status/1/2",
                "/subscribe/unlock", "/comment", "/comment/1/like", "/history/record",
                "/bookshelf/123", "/coin/recharge", "/ai/generate", "/ai/write/continue",
                "/ai/write/polish", "/ai/review/chapter/1", "/ai/review/novel/123",
                "/cover/upload"
        );
        for (String uri : posts) {
            assertThat(allowed("POST", uri)).as("POST %s 必须登录", uri).isFalse();
        }
    }

    @Test
    @DisplayName("拦截的：非 GET/POST 方法与管理端")
    void otherMethodsAndAdmin() {
        assertThat(allowed("PUT", "/novel/123")).isFalse();
        assertThat(allowed("DELETE", "/comment/1")).isFalse();
        assertThat(allowed("GET", "/admin/novel/page")).isFalse();
        // 只读接口也不该因为方法写错就放行（例如将来有人给详情加了 PUT 语义）
        assertThat(allowed("PUT", "/novel/123")).isFalse();
    }

    @Test
    @DisplayName("去掉 context-path 后仍能正确匹配")
    void stripsContextPath() {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/novel/123");
        request.setContextPath("/api");
        assertThat(SaTokenConfig.requestUriWithoutContextPath(request)).isEqualTo("/novel/123");
        assertThat(SaTokenConfig.isGuestReadable(
                "GET", SaTokenConfig.requestUriWithoutContextPath(request))).isTrue();
    }
}
