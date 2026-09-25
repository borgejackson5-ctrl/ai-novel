package com.ainovel.common.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.domain.ResponseDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

/**
 * 全局异常处理
 *
 * <p>HTTP 状态码语义化：业务/参数错误 400、未登录 401、无权限 403、重复提交 409、限流 429、未知异常 500。
 * 响应体仍为统一 {@link ResponseDTO} 结构，前端经 axios 错误分支读取 msg 提示。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 单文件上传上限，与 {@code spring.servlet.multipart.max-file-size} 同源。
     *
     * <p>字段自带默认值：无 Spring 上下文的单测中 {@code @Value} 不会被注入，
     * 此时仍为 20MB，不会退化为 0。
     */
    @Value("${spring.servlet.multipart.max-file-size:20MB}")
    private DataSize maxFileSize = DataSize.ofMegabytes(20);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResponseDTO<Void>> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常: uri={}, err={}", request.getRequestURI(), e.getMessage());
        // 未登录单独映射为 401：前端据此清除会话并跳转登录页，与拦截器抛 NotLoginException 的行为一致。
        // 若不区分，游客访问付费章节只会拿到 400，提示可见但不会引导其登录
        // （该分支由 service 层判定，未经拦截器）。
        // 错误码 → HTTP 状态码。前端按「HTTP 语义 + body 里的 code」双重判断：
        //   401 → 清凭证并跳登录；409 → 幂等冲突，非业务失败；
        //   429 → 提示稍后再试，不要立即重试；其余业务错误均为 400。
        // 限流也在此处分流：抛 RateLimitException 的由下方处理器处理，但 service 层
        // 直接抛 BusinessException(RATE_LIMIT) 的位置同样存在，两处口径必须一致。
        HttpStatus status = switch (e.getErrorCode()) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            // 重复提交属于「状态冲突」而非「参数错误」：同一幂等键的操作尚未结束。
            // 返回 409，调用方可据此决定「使用同一键稍后重试」，而非修改参数
            case REPEAT_SUBMIT -> HttpStatus.CONFLICT;
            case RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(ResponseDTO.error(e.getErrorCode(), e.getMessage()));
    }

    /**
     * 限流：429 + {@code Retry-After}。
     *
     * <p>相比普通业务异常多返回一个响应头：各限流点的窗口长度不同，客户端需要得知「还需等待多久」
     * 只能从此处读取。响应文案中也包含秒数，但响应头才是供中间层（前端拦截器 / 网关）使用的标准位置。
     *
     * <p>使用 429 而非 400 有实际区别：调用方可据此判断「并非请求错误，而是频率过高」，
     * 从而选择退避重试，而非让用户修改参数。
     */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ResponseDTO<Void>> handleRateLimit(RateLimitException e, HttpServletRequest request) {
        log.warn("限流: uri={}, 建议等待 {}s", request.getRequestURI(), e.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ResponseDTO.error(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ResponseDTO<Void>> handleValid(BindException e, HttpServletRequest request) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String msg = validationMessage(fieldError);
        // 校验失败留痕，便于联调与线上问题排查
        log.warn("参数校验失败: uri={}, field={}, rejected={}, msg={}",
                request.getRequestURI(),
                fieldError == null ? "-" : fieldError.getField(),
                fieldError == null ? "-" : String.valueOf(fieldError.getRejectedValue()),
                msg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.error(ErrorCode.PARAM_ERROR, msg));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ResponseDTO<Void>> handleNotLogin(NotLoginException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ResponseDTO.error(ErrorCode.UNAUTHORIZED));
    }

    /**
     * 路径不存在 / 静态资源找不到 → 404。
     *
     * <p>若不处理会进入兜底的 {@code Exception} 分支：**返回 500 并输出完整 ERROR 堆栈**。
     * 线上表现为「地址输入错误」与「服务端故障」在监控中无法区分；
     * 且生产环境关闭的接口文档（swagger / knife4j）正是以此形态存在，
     * 一次扫描即产生大量 ERROR。此处仅记录 warn 且不带堆栈。
     *
     * <p>两个异常均需捕获：默认配置下（静态资源映射开启）不存在的路径会进入资源处理器，
     * 抛 {@link NoResourceFoundException}；prod 关闭 {@code spring.web.resources.add-mappings}
     * 后无资源处理器，改抛 {@link NoHandlerFoundException}。**漏收其一即导致生产加固上线后全部变为 500**。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ResponseDTO<Void>> handleNotFound(Exception e, HttpServletRequest request) {
        log.warn("资源不存在: uri={}, type={}", request.getRequestURI(), e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ResponseDTO.error(ErrorCode.NOT_FOUND, "请求的资源不存在"));
    }

    /**
     * 连不上消息队列（RabbitMQ 不可用 / 网络中断 / 端口配置错误）。
     *
     * <p>若不捕获会被兜底分支吞为 500「系统繁忙」，运维无法区分
     * 「依赖不可用」与「代码缺陷」，而二者的处理方式完全不同，
     * 且每来一个请求都会输出整页堆栈。
     *
     * <p>仅捕获 {@code AmqpConnectException}：它即表示「连接失败」。
     * 更宽泛的 {@code AmqpException} 会同时覆盖序列化失败、队列声明错误这类**真实缺陷**。
     *
     * <p>注意：「报错但数据已写入」的语义缺口已修复，不在该层处理。投递不再挂在
     * 事务的 {@code afterCommit} 上，也不在请求线程中执行：消息先写入 outbox 表
     * （与业务数据同一事务），提交后由独立线程投递，失败则留在表中等待补投（见 {@code MqSender}）。
     * 因此请求线程上基本不会再出现该异常；保留此处理器是为**后续可能出现的同步调用 broker 的代码**
     * 兜底，其提示比「系统繁忙」更准确，且成本极低。
     */
    @ExceptionHandler(AmqpConnectException.class)
    public ResponseEntity<ResponseDTO<Void>> handleMqUnavailable(AmqpConnectException e,
                                                                 HttpServletRequest request) {
        log.warn("消息队列连不上: uri={}, cause={}", request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ResponseDTO.error(ErrorCode.SYSTEM_ERROR, "服务暂时不可用，请稍后再试"));
    }

    /**
     * 请求方法不支持（以 POST 调用 GET 接口，或反之）。
     *
     * <p>与上述「资源不存在」属于同一类：**不捕获会被兜底分支吞为 500「系统繁忙」**，
     * 调用方（脚本、前端、第三方）据此认为「服务端故障」，而实际是自身方法使用错误，
     * 排查方向从一开始即偏差。曾出现：将流式起名接口（GET）以 POST 调用，返回 500。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ResponseDTO<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("请求方法不支持: uri={}, method={}, 支持={}",
                request.getRequestURI(), e.getMethod(), e.getSupportedHttpMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ResponseDTO.error(ErrorCode.PARAM_ERROR,
                        "这个接口不支持 " + e.getMethod() + " 请求"));
    }

    /**
     * 请求体无法解析：JSON 语法错误、编码非 UTF-8、字段类型不匹配、body 为空。
     *
     * <p>此类**均为调用方自身错误**，本应返回 400。曾出现的形态：curl 中中文以 GBK 发送，
     * 请求体不是合法 UTF-8，接口返回 500「系统繁忙」，调用方据此认为服务端故障，
     * 排查方向从一开始即偏差。
     *
     * <p>注意该类异常会进入兜底分支的原因（此结论后续同样适用）：{@code @RestControllerAdvice} 的
     * 异常解析器**注册顺序先于** Spring 自带的 {@code DefaultHandlerExceptionResolver}，
     * 只要 advice 中**存在一个**可匹配的处理器（哪怕是最宽泛的 {@code Exception}），后者即不再执行，
     * 而后者本会将此类异常映射为 400。因此「调用方错误」类的标准异常必须在此逐个显式捕获，
     * 与上述「资源不存在」「请求方法不支持」同理。**决定因素为注册顺序，而非继承关系**：
     * 此类异常中仅部分实现了 {@code ErrorResponse}（缺少参数的那个实现了，
     * 请求体这个没有），因此不能依赖框架自行兜底。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResponseDTO<Void>> handleUnreadableBody(HttpMessageNotReadableException e,
                                                                  HttpServletRequest request) {
        // 根因单独记录一行日志：其中有效信息为其自身描述（「Invalid UTF-8 start byte 0xca」
        // 「Unexpected end-of-input」）。**但不放入返回给用户的 message**，
        // 该描述随 Jackson 版本与语言变化，等同于引入一处不可控的文案依赖。
        log.warn("请求体读不出来: uri={}, cause={}", request.getRequestURI(), rootCauseLine(e));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.error(ErrorCode.PARAM_ERROR, "请求内容格式有误，请检查请求体"));
    }

    /**
     * 查询参数 / 路径变量 / 表单部件错误，同属调用方问题，本应返回 400。
     *
     * <p>典型情况：未传必填参数、{@code /xxx/abc} 中 id 非数字、multipart 缺少文件。
     * 不捕获同样会返回 500（原因见上一方法）。
     *
     * <p>关于 {@code HandlerMethodValidationException}：它负责**方法参数上**的校验
     * （需在类上开启 {@code @Validated} 才生效），本项目当前未使用（表单类上的 {@code @Min}
     * 走的是 {@code MethodArgumentNotValidException}）。捕获它是为了避免后续实际启用时返回 500，
     * 成本为一行代码。
     */
    @ExceptionHandler({ServletRequestBindingException.class,
                       MissingServletRequestPartException.class,
                       MethodArgumentTypeMismatchException.class,
                       HandlerMethodValidationException.class})
    public ResponseEntity<ResponseDTO<Void>> handleBadRequestParam(Exception e, HttpServletRequest request) {
        log.warn("请求参数有问题: uri={}, type={}, detail={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.error(ErrorCode.PARAM_ERROR, badParamMessage(e)));
    }

    /**
     * 上传请求的大小超出 {@code spring.servlet.multipart.max-file-size} → 413。
     *
     * <p>必须单独捕获而不能依赖 {@link MultipartException} 的处理器：
     * {@code MaxUploadSizeExceededException} 是其子类，若只声明父类，超限上传会被答复为
     * 「请以 multipart/form-data 提交」——把「文件过大」说成「格式不对」，是误导性的排查方向。
     * Spring 按最具体类型选择处理器，因此两者同时声明时本方法优先。
     *
     * <p>上限值优先取异常携带的值，为未知时回退到本类读到的配置值：上限由容器
     * （Tomcat 的 Servlet Part 实现）判定时，{@code getMaxUploadSize()} 返回 {@code -1} 表示未知，
     * 直接用它渲染会得到「单个文件最大 0MB」。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResponseDTO<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e,
                                                                 HttpServletRequest request) {
        long fromException = e.getMaxUploadSize();
        long maxBytes = fromException > 0 ? fromException : maxFileSize.toBytes();
        log.warn("上传文件超过大小上限: uri={}, 异常携带={} 字节, 生效上限={} 字节",
                request.getRequestURI(), fromException, maxBytes);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ResponseDTO.error(ErrorCode.PARAM_ERROR,
                        "文件过大，单个文件最大 " + (maxBytes / 1024 / 1024) + "MB"));
    }

    /**
     * 上传请求的构造有误，最常见的是以非 {@code multipart/form-data} 的 {@code Content-Type}
     * 提交到文件接口 → 400。此时 Spring 在解析参数阶段即抛出，异常文本为
     * {@code Current request is not a multipart request}，属调用方请求格式问题，不应报 500。
     *
     * <p>覆盖的另一个场景是 multipart 请求体本身无法解析（边界串缺失或被截断）。
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ResponseDTO<Void>> handleMultipart(MultipartException e,
                                                            HttpServletRequest request) {
        log.warn("上传请求格式有误: uri={}, detail={}", request.getRequestURI(), rootCauseLine(e));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ResponseDTO.error(ErrorCode.PARAM_ERROR, "请以 multipart/form-data 形式上传文件"));
    }

    /**
     * 请求的 {@code Content-Type} 与接口要求不符（例如 {@code application/json} 的接口
     * 收到 {@code text/plain}）→ 415。
     *
     * <p>该场景可正常返回 JSON 响应体：{@code Content-Type} 表示「发送方的数据类型」，
     * 与「可接收的类型」（Accept）无关，不影响写回响应。
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ResponseDTO<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        log.warn("请求的 Content-Type 不支持: uri={}, contentType={}, 支持={}",
                request.getRequestURI(), e.getContentType(), e.getSupportedMediaTypes());
        MediaType ct = e.getContentType();
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ResponseDTO.error(ErrorCode.PARAM_ERROR,
                        "这个接口不接受该 Content-Type：" + (ct == null ? "（未提供）" : ct)));
    }

    /**
     * 客户端仅接受接口无法产出的类型（例如 {@code Accept: application/xml}）→ 406。
     *
     * <p>**该方法刻意不返回响应体**，因为返回也无意义：响应体类型需按 Accept 再协商一次，
     * 而该次协商**必然失败**（原因相同），于是异常再次抛出并回到此处。
     * 此处曾被按 415 的写法返回 JSON body，最终结果为
     * <b>404「请求的资源不存在」</b>，比原本的 500 更具误导性，因为它将「类型无法协商」
     * 表述为「资源不存在」。
     *
     * <p>Spring 官方文档（@ExceptionHandler 的 Return Values 一节）已说明该写法：
     * {@code void} 返回类型且带 {@code ServletResponse} 参数 ⇒ **视为已完全处理**，
     * 不再继续执行解析链。这与本类中 {@code handleIOException} 在「响应已提交」时
     * 返回 null 的处理思路一致。
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public void handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException e,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        log.warn("客户端不接受接口能产出的类型: uri={}, detail={}",
                request.getRequestURI(), e.getMessage());
        response.setStatus(HttpStatus.NOT_ACCEPTABLE.value());
    }

    /** 参数类异常返回给用户的文案。**不回显用户传入的值**，避免被当作内容渲染 */
    private static String badParamMessage(Exception e) {
        if (e instanceof MissingServletRequestParameterException ex) {
            return "缺少必填参数：" + ex.getParameterName();
        }
        if (e instanceof MethodArgumentTypeMismatchException ex) {
            return "参数 " + ex.getName() + " 的值不对";
        }
        if (e instanceof MissingServletRequestPartException ex) {
            return "缺少上传的文件：" + ex.getRequestPartName();
        }
        return "请求参数不完整或格式不对";
    }

    /**
     * 校验类异常（{@code Validator} 抛出）返回给用户的文案。
     *
     * <p>区分两种失败：注解校验失败（{@code @Min} / {@code @Size} 等）的默认消息由开发者撰写，
     * 可直接透出；**类型绑定失败**（如 {@code pageNum=abc} 绑到 {@code Long}）的默认消息是 Spring
     * 生成的诊断文本，形如
     * {@code Failed to convert property value of type 'java.lang.String' to required type 'java.lang.Long' for property 'pageNum'}，
     * 含 Java 类名、属性名与原始入参。此类文本属实现细节，改为中性文案，与
     * {@link #badParamMessage} 处理 {@code @RequestParam} 类型不匹配的方式一致。
     *
     * <p>保留字段名：它取自调用方自己的请求，不构成额外信息暴露，且是定位问题所必需的。
     */
    private static String validationMessage(FieldError fieldError) {
        if (fieldError == null) {
            return "参数校验失败";
        }
        if (fieldError.isBindingFailure()) {
            return "参数 " + fieldError.getField() + " 的值不对";
        }
        String msg = fieldError.getDefaultMessage();
        return (msg == null || msg.isBlank()) ? "参数校验失败" : msg;
    }

    /** 取最内层 cause 的描述，压缩为一行并截断，便于日志阅读 */
    private static String rootCauseLine(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        String line = (m == null || m.isBlank()) ? c.getClass().getSimpleName() : m.replaceAll("\\s+", " ").trim();
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    @ExceptionHandler({NotPermissionException.class, NotRoleException.class})
    public ResponseEntity<ResponseDTO<Void>> handleNotPermission(Exception e, HttpServletRequest request) {
        log.warn("无权限访问: uri={}, type={}", request.getRequestURI(), e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ResponseDTO.error(ErrorCode.FORBIDDEN, "无权限访问"));
    }

    /**
     * 异步请求超时：SSE 流已开启但始终未结束（模型长时间未输出完毕）。
     *
     * <p>官方 javadoc 已说明其语义：*"By default the exception will be handled as a 503 error."*
     * 若不显式捕获，会被兜底分支吞为 500「系统繁忙」，而二者对运维的含义完全不同：
     * 503 表示「服务繁忙，稍后重试」，500 表示「代码缺陷」。前者日志为一行 WARN，后者为整页堆栈。
     *
     * <p>**按「响应是否已提交」分两个分支**：SSE 只要推送过任意内容，响应即已提交，
     * 此时再写 body 只会再次抛出异常（与下方 {@link #handleIOException} 同理）。
     * 超时两种情况均可能出现：模型未输出任何内容即超时（未提交），或输出部分后停滞（已提交）。
     *
     * <p>注意：**此处仅处理状态码，不处理额度。**超时是否退还额度属于业务口径
     * （「失败退、主动停止不退」），由调用方决定，不在此处一并处理。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<ResponseDTO<Void>> handleAsyncTimeout(AsyncRequestTimeoutException e,
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) {
        if (response.isCommitted()) {
            log.warn("异步请求超时（响应已提交，流只能到此为止）: uri={}", request.getRequestURI());
            return null;   // 响应已提交，再写 body 只会再次抛出异常
        }
        log.warn("异步请求超时（一个字都还没推出去）: uri={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ResponseDTO.error(ErrorCode.SYSTEM_ERROR, "这次生成等太久了，请重试"));
    }

    /**
     * 客户端中途断开（关闭页面、点击「停止」、网络中断）。
     *
     * <p>**判据为「响应已提交」，而非「异常信息中是否包含某个词」**：Tomcat 向一条已断开的
     * socket 执行 flush 时抛出的是普通 {@link IOException}，其 message 随系统语言变化
     * （中文 Windows 上为「你的主机中的软件中止了一个已建立的连接」），
     * 依据文案匹配等同于引入一处不可控的文案依赖。而「响应已提交 + IOException」的组合
     * 基本只有一种解释：客户端已断开。
     *
     * <p>必须单独捕获的原因：流式接口（SSE）在收尾时必然进入该分支。若不捕获，
     * 用户每点击一次「停止」即产生三条 ERROR 与整页堆栈（响应已提交、无法写 body，
     * 异常继续向上传播，容器再记两条），导致线上 ERROR 日志失去参考价值。
     *
     * <p>返回值为 {@code null}：响应已提交，再写 body 只会再次抛出异常。
     *
     * <p>同时覆盖 {@code AsyncRequestNotUsableException}（Spring 6 用于表示
     * 「响应已不可写」的异常），它 extends {@link IOException}，因此天然进入此处。
     * 日志中带上类名即为此目的：看到该类型即可判定为「客户端不再接收」，
     * 而非「服务端写入失败」。
     */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ResponseDTO<Void>> handleIOException(IOException e, HttpServletRequest request,
                                                               HttpServletResponse response) {
        if (response.isCommitted()) {
            log.warn("客户端已断开，响应没能写完: uri={}, type={}（流式接口收尾时的正常现象，不是故障）",
                    request.getRequestURI(), e.getClass().getSimpleName());
            return null;
        }
        log.error("系统异常: uri={}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDTO.error(ErrorCode.SYSTEM_ERROR));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseDTO<Void>> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常: uri={}", request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ResponseDTO.error(ErrorCode.SYSTEM_ERROR));
    }
}
