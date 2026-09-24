package com.ainovel.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.ainovel.common.web.SkipAsyncDispatchInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Sa-Token 拦截器：全局登录校验（注解鉴权由 SaInterceptor 默认开启）
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 允许未登录访问的只读接口（URI 正则）。这些接口仅用于查看，不包含个人数据，
     * 也不修改任何状态，使新用户首次打开即可看到内容，无需先注册。
     *
     * <p>使用正则逐条列出，而非 {@code /novel/*} 这类通配符：通配符会连带放开
     * {@code POST /novel/publish}、{@code /novel/save}、{@code /novel/reindex}
     * 等同层级的写接口（这些接口与详情 {@code /novel/{id}} 仅相差一个路径段）。
     *
     * <p>个人数据（书架 / 历史 / 站内信 / 余额 / 进度 / 我的作品）与所有写操作、
     * AI 创作接口均不在此列，仍必须登录。
     */
    private static final List<Pattern> GUEST_READABLE = List.of(
            Pattern.compile("^/novel/\\d+$"),                   // 作品详情
            Pattern.compile("^/novel/(page|search)$"),          // 书库列表 / 全文搜索
            Pattern.compile("^/novel/by-author/\\d+$"),         // 某位作者的作品
            Pattern.compile("^/rank/[a-z]+$"),                  // 榜单
            Pattern.compile("^/category/list$"),                // 分类
            Pattern.compile("^/chapter/\\d+$"),                 // 单章元数据（不含正文，付费章也能取）
            Pattern.compile("^/chapter/page/\\d+$"),            // 读者视角的目录
            Pattern.compile("^/chapter/\\d+/content$"),         // 章节正文（付费章仍要登录，挡在 service 层）
            Pattern.compile("^/comment/(page|chapter)/\\d+$"),  // 评论列表
            Pattern.compile("^/comment/replies/\\d+$")          // 评论回复
    );

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // order(0)：登录校验排在限流（WebRateLimitConfig，order 10）之前。
        // 限流按「登录用户 id / 客户端 IP」计数，需先识别请求归属；否则所有
        // 登录用户会共用同一 IP 维度的额度。跨 WebMvcConfigurer 的顺序由 InterceptorRegistry
        // 按 order 统一排序决定，不依赖配置类的加载顺序，因此此处必须显式指定。
        registry.addInterceptor(new SkipAsyncDispatchInterceptor(new SaInterceptor(handle -> {
                    // 只读的读者侧接口放行给游客；其余一律要求登录态。
                    // 注解鉴权不受影响：SaInterceptor 在调用该函数之前，
                    // 已对 @SaCheckRole / @SaCheckPermission 执行过一次校验（运维入口依赖该机制）。
                    if (!guestReadableFromCurrentRequest()) {
                        StpUtil.checkLogin();
                    }
                })))
                .addPathPatterns("/**")
                .order(0)
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/register",
                        "/auth/code",
                        "/auth/reset-password",
                        "/coin/pay/notify",
                        "/doc.html",
                        "/webjars/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/favicon.ico",
                        "/error"
                );
    }

    /** 从当前请求上下文取 method / URI，判断是否放行游客 */
    private static boolean guestReadableFromCurrentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        return isGuestReadable(request.getMethod(), requestUriWithoutContextPath(request));
    }

    /**
     * 判据本体（纯函数，便于单测）。
     *
     * <p>除智能搜索（语义只读，但接口声明为 POST）外仅放行 GET：
     * 写操作不应因路径形似只读而被放行。
     */
    static boolean isGuestReadable(String method, String uri) {
        if ("POST".equals(method)) {
            return "/novel/search/smart".equals(uri);
        }
        if (!"GET".equals(method)) {
            return false;
        }
        return GUEST_READABLE.stream().anyMatch(p -> p.matcher(uri).matches());
    }

    /** 去掉 context-path，得到与 {@link #GUEST_READABLE} 里写法一致的路径 */
    static String requestUriWithoutContextPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
