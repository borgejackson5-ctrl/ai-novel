package com.ainovel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容协商策略的边界：重点是不得顺带把「客户端不肯收 JSON」也放行，
 * 那会破坏 {@code GlobalExceptionHandler#handleMediaTypeNotAcceptable} 维持的 406 语义。
 */
@DisplayName("内容协商：SSE 客户端补 JSON 兜底")
class ContentNegotiationConfigTest {

    private final ContentNegotiationConfig.SseJsonFallbackNegotiationStrategy strategy =
            new ContentNegotiationConfig.SseJsonFallbackNegotiationStrategy();

    private List<MediaType> negotiate(String accept) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (accept != null) {
            request.addHeader("Accept", accept);
        }
        NativeWebRequest webRequest = new ServletWebRequest(request);
        return strategy.resolveMediaTypes(webRequest);
    }

    @Test
    @DisplayName("只要 text/event-stream 时补上 application/json —— 这是本次修复的场景")
    void sseOnly_getsJsonFallback() throws Exception {
        List<MediaType> types = negotiate("text/event-stream");

        assertThat(types)
                .as("不补 JSON 的话，异常处理器写 body 时会再协商一次并失败，最终 500 空响应体")
                .anyMatch(t -> t.includes(MediaType.APPLICATION_JSON));
        assertThat(types)
                .as("原样保留 event-stream，SSE 正常流的 handler 映射不能受影响")
                .anyMatch(t -> t.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    @DisplayName("已经接受 JSON 的（含 */*）不加任何东西")
    void acceptsJson_untouched() throws Exception {
        assertThat(negotiate("application/json")).hasSize(1);
        assertThat(negotiate("text/event-stream, application/json"))
                .as("已有 JSON 就不该重复追加")
                .doesNotHaveDuplicates();
        assertThat(negotiate("*/*"))
                .as("浏览器默认发的 */* 已经包含 JSON")
                .containsExactly(MediaType.ALL);
    }

    @Test
    @DisplayName("客户端不肯收 JSON 时**不补** —— 保住 406 语义")
    void notAcceptingJson_stillNotAcceptable() throws Exception {
        assertThat(negotiate("application/xml"))
                .as("补了 JSON 就等于把客户端明确拒绝的类型硬塞给它；这条路径要继续走 406")
                .noneMatch(t -> t.includes(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("没有 Accept 头时行为与原生一致")
    void missingAccept_behavesLikeDefault() throws Exception {
        assertThat(strategy.resolveMediaTypes(new ServletWebRequest(new MockHttpServletRequest())))
                .containsExactly(MediaType.ALL);
    }
}
