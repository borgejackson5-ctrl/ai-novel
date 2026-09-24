package com.ainovel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容协商：为 SSE 客户端补充一条 JSON 兜底，使建流之前的校验异常能够返回可读信息。
 *
 * <p>解决的问题：三个流式接口（{@code /ai/write/continue}、{@code /ai/write/polish}、
 * {@code /ai/generate/stream}）声明为 {@code produces = text/event-stream}，而浏览器
 * {@code EventSource} 等客户端仅发送 {@code Accept: text/event-stream}。接口中「扣费前先校验」
 * 抛出的 {@code BusinessException} 由异常处理器转换为 JSON 响应体，但该 body 的类型需按 Accept
 * 再次协商，而列表中没有 {@code application/json}，协商失败并抛出
 * {@code HttpMediaTypeNotAcceptableException}。该二次异常发生在异常处理器内部，
 * 不会回到解析链，最终表现为 500 且响应体为空。
 *
 * <p>验证结果：同一请求携带 {@code Accept: text/event-stream} 时返回 500 且 body 为空；
 * 不携带时返回 400 与「选中的内容太短了，至少选一段完整的话」。即接口已实现的中文提示
 * 在返回途中丢失。
 *
 * <p>仅在客户端明确请求 SSE 时补充的原因：全局补充 JSON 会使
 * {@code Accept: application/xml} 等客户端不接受 JSON 的请求也返回 200 + JSON，
 * 违反 HTTP 语义；而 {@code GlobalExceptionHandler#handleMediaTypeNotAcceptable}
 * 当前正是依赖「协商失败 ⇒ 406」维持该语义。收窄至 SSE 后两条路径均得到保留：
 * SSE 客户端获得可读的 JSON 错误，其他客户端仍返回 406。
 */
@Configuration
public class ContentNegotiationConfig implements WebMvcConfigurer {

    @Override
    public void configureContentNegotiation(@NonNull ContentNegotiationConfigurer configurer) {
        configurer.strategies(List.of(new SseJsonFallbackNegotiationStrategy()));
    }

    /**
     * 在默认的按 Accept 头协商基础上增加一条：客户端仅请求 {@code text/event-stream}
     * 且不含 {@code application/json} 时，追加 {@code application/json}。
     *
     * <p>仅影响协商结果，不影响 SSE 正常路径：{@code SseEmitter} 的写出由
     * {@code ResponseBodyEmitterReturnValueHandler} 处理，其不读取内容协商；而流声明的
     * {@code produces = text/event-stream} 仍在列表中，handler 映射不受影响。
     */
    static class SseJsonFallbackNegotiationStrategy extends HeaderContentNegotiationStrategy {

        @Override
        @NonNull
        public List<MediaType> resolveMediaTypes(@NonNull NativeWebRequest request)
                throws HttpMediaTypeNotAcceptableException {
            List<MediaType> types = new ArrayList<>(super.resolveMediaTypes(request));

            boolean lacksJson = types.stream().noneMatch(t -> t.includes(MediaType.APPLICATION_JSON));
            boolean wantsSse = types.stream().anyMatch(t -> t.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
            if (lacksJson && wantsSse) {
                types.add(MediaType.APPLICATION_JSON);
            }
            return types;
        }
    }
}
