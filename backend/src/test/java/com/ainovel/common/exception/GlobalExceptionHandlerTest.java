package com.ainovel.common.exception;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.domain.ResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.validation.BindException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局异常处理的 HTTP 状态码语义测试。
 *
 * <p>背景：{@code NoResourceFoundException} 在未专门处理时落入兜底的
 * {@code Exception} 分支。后果有两条，都不是「功能损坏」而是「看起来像损坏」：
 * <ol>
 *   <li>错误地址返回 500，线上监控中「敲错 URL」与「服务真的故障」表现相同；</li>
 *   <li>每次都要打印完整 ERROR 堆栈。生产中被关闭的接口文档正是以该形态存在
 *       （{@code /v3/api-docs} 找不到资源），一次路径扫描就能产生满屏 ERROR。</li>
 * </ol>
 *
 * <p>后续发现这是整族问题：{@code @RestControllerAdvice} 的异常解析器注册顺序在
 * Spring 自带的 {@code DefaultHandlerExceptionResolver} 之前，因此凡未被此处显式接收的
 * 标准异常，都不会由该解析器映射为 4xx，而是落入兜底变成 500。
 * 「调用方自己写错」的请求（请求体损坏、缺参数、id 不是数字、Content-Type 不正确、
 * 文件接口收到非 multipart 请求、上传超过大小上限等）全部返回 500「系统繁忙」。
 * 最后一节是针对该性质的结构性断言，逐个类型约束覆盖情况。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");

    @Test
    @DisplayName("资源不存在 → 404（而不是 500）")
    void noResourceFound_returns404() {
        NoResourceFoundException e = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "v3/api-docs");

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleNotFound(e, request);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(), "未知路径必须是 404，不能是 500");
        ResponseDTO<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.NOT_FOUND.getCode(), body.getCode());
        assertEquals(Boolean.FALSE, body.getSuccess());
    }

    @Test
    @DisplayName("无处理器（prod 关掉静态资源映射后走这条）同样 → 404")
    void noHandlerFound_returns404() {
        NoHandlerFoundException e = new NoHandlerFoundException("GET", "/doc.html", null);

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleNotFound(e, request);

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode(),
                "prod 关掉 spring.web.resources.add-mappings 后抛的是 NoHandlerFoundException，"
                        + "收不到这条就会整个生产环境把 404 都变成 500");
    }

    @Test
    @DisplayName("业务异常仍是 400、未登录仍是 401（改动没有波及既有语义）")
    void otherHandlersKeepTheirStatus() {
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleBusiness(new BusinessException(ErrorCode.PARAM_ERROR, "参数不对"), request)
                        .getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED,
                handler.handleNotLogin(null).getStatusCode());
    }

    @Test
    @DisplayName("业务异常里「未登录」那一档给 401，其余仍是 400")
    void businessUnauthorized_returns401() {
        // service 层判定的「未登录」（如游客访问付费章）也要让前端能识别为登录态失效，
        // 否则只弹出一句提示、不会引导用户前往登录页
        assertEquals(HttpStatus.UNAUTHORIZED,
                handler.handleBusiness(new BusinessException(ErrorCode.UNAUTHORIZED, "登录后可阅读付费章节"), request)
                        .getStatusCode());
        // 其余业务异常不受影响
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleBusiness(new BusinessException(ErrorCode.FORBIDDEN, "无权限"), request)
                        .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleBusiness(new BusinessException(ErrorCode.PARAM_ERROR, "参数不对"), request)
                        .getStatusCode());
    }

    @Test
    @DisplayName("真正的未知异常仍是 500（兜底没被削弱）")
    void unknownException_still500() {
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                handler.handleException(new IllegalStateException("boom"), request).getStatusCode());
    }

    @Test
    @DisplayName("响应已提交时的 IOException ⇒ 认成「客户端断开」，不再返 body、也不打 ERROR 堆栈")
    void ioExceptionAfterCommit_isClientAbort() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        ResponseEntity<ResponseDTO<Void>> resp =
                handler.handleIOException(new IOException("你的主机中的软件中止了一个已建立的连接"), request, response);

        // 响应已提交，再写 body 只会再抛一次异常、再产生一条 ERROR
        assertNull(resp, "客户端已经走了，这里不该再返回任何响应体");
    }

    @Test
    @DisplayName("响应还没提交的 IOException 仍是真故障（500）—— 别把兜底一起放掉")
    void ioExceptionBeforeCommit_still500() {
        MockHttpServletResponse response = new MockHttpServletResponse();   // 默认未提交

        ResponseEntity<ResponseDTO<Void>> resp =
                handler.handleIOException(new IOException("磁盘写满了"), request, response);

        assertNotNull(resp, "响应都没提交，这就是真故障，不能一并降级");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    // ==================== 「调用方自己写错」的那一族：都该是 4xx ====================

    @Test
    @DisplayName("请求体读不出来（JSON 坏 / 编码不是 UTF-8 / 字段类型不对）→ 400，不是 500")
    void unreadableBody_returns400() {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException(
                "JSON parse error: Invalid UTF-8 start byte 0xca",
                new RuntimeException("Invalid UTF-8 start byte 0xca"));

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleUnreadableBody(e, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "这一类全是调用方写错，回 500 会让对方以为是服务端故障");
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), resp.getBody().getCode());
        // 返回给用户的是提示语；Jackson 的原始信息只进入日志，它随版本与语言变化
        assertFalse(resp.getBody().getMsg().contains("Invalid UTF-8"),
                "别把 Jackson 的原话放进给用户的提示：那句文案不稳定，等于是翻译炸弹");
    }

    @Test
    @DisplayName("缺必填参数 → 400，且告诉对方缺的是哪个参数")
    void missingParam_returns400WithName() {
        MissingServletRequestParameterException e =
                new MissingServletRequestParameterException("type", "String");

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleBadRequestParam(e, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMsg().contains("type"),
                "「参数不完整」这种话没法用，得说是哪一个");
    }

    @Test
    @DisplayName("参数值类型不对 → 400，且不回显对方传来的值")
    void typeMismatch_returns400() {
        MethodArgumentTypeMismatchException e =
                new MethodArgumentTypeMismatchException("<script>x</script>", Long.class, "novelId", null, null);

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleBadRequestParam(e, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMsg().contains("novelId"));
        assertFalse(resp.getBody().getMsg().contains("script"),
                "别把对方传来的值原样回显 —— 它在别的页面被当内容渲染时就是个注入点");
    }

    @Test
    @DisplayName("multipart 少传了文件 → 400，且告诉缺的是哪个 part")
    void missingPart_returns400WithName() {
        MissingServletRequestPartException e = new MissingServletRequestPartException("file");

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleBadRequestParam(e, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMsg().contains("file"));
    }

    @Test
    @DisplayName("上传接口收到非 multipart 请求 → 400，不是 500")
    void notMultipartRequest_returns400() {
        // Spring 6.2 在解析 MultipartFile 参数、而请求不是 multipart 时抛出该异常
        // （RequestParamMethodArgumentResolver#handleMissingValueInternal）。
        // 未显式接收时落入兜底分支，调用方拿到「系统繁忙」，会去查服务端故障
        MultipartException e = new MultipartException("Current request is not a multipart request");

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleMultipart(e, request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(ErrorCode.PARAM_ERROR.getCode(), resp.getBody().getCode());
        assertFalse(resp.getBody().getMsg().contains("multipart request"),
                "异常原话是英文实现细节，不进给调用方的提示");
    }

    @Test
    @DisplayName("上传超过大小上限 → 413，且带上限值")
    void uploadTooLarge_returns413WithLimit() {
        MaxUploadSizeExceededException e = new MaxUploadSizeExceededException(20L * 1024 * 1024);

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleUploadTooLarge(e, request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode(),
                "文件过大不是请求格式错误：报 400 会把排查方向引向 multipart 写法");
        assertTrue(resp.getBody().getMsg().contains("20MB"),
                "上限取自异常携带的配置值，改 spring.servlet.multipart.max-file-size 时文案自动跟上");
    }

    @Test
    @DisplayName("上限由容器判定（异常携带 -1 表示未知）→ 回退配置值，不能渲染成「最大 0MB」")
    void uploadTooLarge_unknownLimit_fallsBackToConfiguredSize() {
        // 实测：上限由 Tomcat 的 Servlet Part 实现判定时，Spring 传入 -1
        MaxUploadSizeExceededException e = new MaxUploadSizeExceededException(-1);

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleUploadTooLarge(e, request);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.getStatusCode());
        assertTrue(resp.getBody().getMsg().contains("20MB"),
                "-1 是「未知」而非上限，直接相除会得到「单个文件最大 0MB」。实际：" + resp.getBody().getMsg());
    }

    @Test
    @DisplayName("表单字段类型绑定失败 → 中性文案，不透出 Spring 的转换诊断")
    void bindingFailure_usesNeutralMessage() {
        // 该 FieldError 的默认消息就是 Spring 在类型转换失败时生成的诊断文本，
        // 含 Java 类名与属性名。绑定失败由 bindingFailure=true 标记
        FieldError fieldError = new FieldError("novelQueryForm", "pageNum", "abc", true,
                new String[]{"typeMismatch"}, null,
                "Failed to convert property value of type 'java.lang.String' to required type "
                        + "'java.lang.Long' for property 'pageNum'");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "novelQueryForm");
        binding.addError(fieldError);

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleValid(new BindException(binding), request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().getMsg().contains("pageNum"), "字段名要留，否则定位不到是哪个参数");
        assertFalse(resp.getBody().getMsg().contains("java.lang"),
                "Java 类名属实现细节，不进给调用方的提示");
    }

    @Test
    @DisplayName("注解校验失败仍透出开发者写的消息（中性化不能把有用信息一起抹掉）")
    void annotationValidation_keepsCustomMessage() {
        FieldError fieldError = new FieldError("ResumeSerialForm", "reason", "略", false,
                new String[]{"Size"}, null, "理由需在 10~500 字之间");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "ResumeSerialForm");
        binding.addError(fieldError);

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleValid(new BindException(binding), request);

        assertEquals("理由需在 10~500 字之间", resp.getBody().getMsg());
    }

    @Test
    @DisplayName("Content-Type 不是接口要的 → 415（不是 500）")
    void mediaTypeNotSupported_returns415() {
        HttpMediaTypeNotSupportedException e = new HttpMediaTypeNotSupportedException(
                MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<ResponseDTO<Void>> resp = handler.handleMediaTypeNotSupported(e, request);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode());
        assertTrue(resp.getBody().getMsg().contains("text/plain"),
                "得把「你发的是什么」说出来，否则对方不知道要改哪儿");
    }

    @Test
    @DisplayName("Accept 不匹配 → 406，而且**一个字节的响应体都不写**")
    void mediaTypeNotAcceptable_returns406WithoutBody() {
        HttpMediaTypeNotAcceptableException e =
                new HttpMediaTypeNotAcceptableException("Could not find acceptable representation");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handleMediaTypeNotAcceptable(e, request, response);

        assertEquals(HttpStatus.NOT_ACCEPTABLE.value(), response.getStatus());
        // 该断言来自一次实现缺陷：最初照 415 的写法返回 JSON body，于是响应要按 Accept
        // 再协商一次、必然再次失败，最终退化为 404「请求的资源不存在」，
        // 其误导性大于此前的 500（把「类型不匹配」表述为「资源不存在」）。
        assertEquals(0, response.getContentAsByteArray().length,
                "这里一旦写 body，就会再触发一次内容协商失败；只能只设状态码");
    }

    @Test
    @DisplayName("异步请求超时（响应还没提交）→ 503，不是 500")
    void asyncTimeoutBeforeCommit_returns503() {
        MockHttpServletResponse response = new MockHttpServletResponse();   // 默认未提交

        ResponseEntity<ResponseDTO<Void>> resp =
                handler.handleAsyncTimeout(new AsyncRequestTimeoutException(), request, response);

        // 官方 javadoc："By default the exception will be handled as a 503 error."
        // 落入兜底即为 500「系统繁忙」，而 503「服务暂不可用」与 500「代码缺陷」对运维是两回事
        assertNotNull(resp);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertFalse(resp.getBody().getMsg().isBlank(), "得给一句人话，不能只有状态码");
    }

    @Test
    @DisplayName("异步请求超时但流已经推了一半（响应已提交）→ 只记日志，不写 body")
    void asyncTimeoutAfterCommit_writesNothing() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        ResponseEntity<ResponseDTO<Void>> resp =
                handler.handleAsyncTimeout(new AsyncRequestTimeoutException(), request, response);

        assertNull(resp, "响应提交完了，再写 body 只会再抛一次（和客户端断开那条同一个道理）");
    }

    /**
     * 守门：这一族「调用方写错」的标准异常必须都被显式接收。
     *
     * <p>使用反射断言覆盖、而不逐个调用方法的原因：该缺陷的形态是「遗漏」：
     * 遗漏哪个类型在代码上完全看不出来，只有确实有人那样请求时才会变成 500，
     * 而 500 的文案是「系统繁忙」，会把调用方引向「服务端故障」。
     * 因此这里约束的是「覆盖」这一性质本身：新使用某类参数绑定特性时，
     * 若其异常未被接收，本测试即失败。
     */
    @Test
    @DisplayName("守门：客户端错误的标准异常都被显式收下（漏一个就是一片 500）")
    void clientErrorExceptionsAreAllHandled() {
        List<Class<?>> handled = new ArrayList<>();
        for (Method m : GlobalExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler ann = m.getAnnotation(ExceptionHandler.class);
            if (ann != null) {
                handled.addAll(Arrays.asList(ann.value()));
            }
        }

        List<Class<? extends Exception>> mustBeHandled = List.of(
                HttpMessageNotReadableException.class,           // 请求体坏 / 编码不对 / 字段类型不对
                MissingServletRequestParameterException.class,   // 缺必填查询参数
                MethodArgumentTypeMismatchException.class,       // 路径变量或参数不是合法类型
                MissingServletRequestPartException.class,        // multipart 缺文件
                MultipartException.class,                        // 文件接口收到非 multipart 请求体
                MaxUploadSizeExceededException.class,            // 上传超过大小上限（须与父类分开声明）
                HttpMediaTypeNotSupportedException.class,        // Content-Type 不支持
                HttpMediaTypeNotAcceptableException.class,       // Accept 谈不拢
                HttpRequestMethodNotSupportedException.class,    // 方法用错
                AsyncRequestTimeoutException.class,              // 异步（SSE）超时，官方语义是 503
                NoResourceFoundException.class,                  // 路径不存在
                NoHandlerFoundException.class);

        for (Class<? extends Exception> t : mustBeHandled) {
            boolean covered = handled.stream().anyMatch(h -> h.isAssignableFrom(t));
            assertTrue(covered, t.getSimpleName() + " 没有在 GlobalExceptionHandler 里被显式收下 —— "
                    + "它会掉进兜底的 Exception 分支变成 500「系统繁忙」"
                    + "（原因：advice 的解析器注册顺序在 Spring 自带的前面）。"
                    + "上传相关的两类已实测：非 multipart 请求与超过 20MB 的文件，原先都是 500。");
        }
    }

    /**
     * 守门：上传超限必须与父类分开声明。
     *
     * <p>覆盖性断言用的是 {@code isAssignableFrom}，因此声明父类 {@link MultipartException} 会让
     * {@link MaxUploadSizeExceededException} 在上一测试里「看起来已覆盖」。实际两者语义不同：
     * 前者是请求格式不对（400），后者是文件过大（413）。只声明父类时超限上传被答复为
     * 「请以 multipart/form-data 形式上传文件」，把「文件太大」说成「格式不对」。
     */
    @Test
    @DisplayName("守门：超限上传有独立处理器，不被父类的处理器吞掉")
    void uploadTooLarge_hasOwnHandler() {
        boolean exact = false;
        for (Method m : GlobalExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler ann = m.getAnnotation(ExceptionHandler.class);
            if (ann == null) {
                continue;
            }
            if (Arrays.asList(ann.value()).contains(MaxUploadSizeExceededException.class)) {
                exact = true;
            }
        }
        assertTrue(exact, "MaxUploadSizeExceededException 必须单独声明：Spring 按最具体类型选择处理器，"
                + "与 MultipartException 合并声明时超限上传会走错分支");
    }
}
