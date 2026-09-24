package com.ainovel.module.ai.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ainovel.common.constant.SseConstant;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.common.exception.StreamCancelledException;
import com.ainovel.common.metrics.BusinessMetrics;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.common.util.SsePayload;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.domain.form.AiConfigForm;
import com.ainovel.module.ai.domain.form.AiGenerateForm;
import com.ainovel.module.ai.domain.form.AiReviewStartForm;
import com.ainovel.module.ai.domain.form.UserAiKeyForm;
import com.ainovel.module.ai.domain.vo.AiReviewIssueVO;
import com.ainovel.module.ai.domain.vo.AiReviewOverviewVO;
import com.ainovel.module.ai.domain.vo.AiReviewTaskVO;
import com.ainovel.module.ai.domain.vo.UserAiConfigVO;
import com.ainovel.module.ai.domain.vo.ChapterReviewVO;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.ai.service.AiReviewTaskService;
import com.ainovel.module.ai.service.AiService;
import com.ainovel.module.ai.service.ChapterReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * AI 接口：内容生成 + 提交审核 + 配置管理
 */
@Slf4j
@Tag(name = "AI 能力")
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiService aiService;

    private final AiConfigService aiConfigService;

    private final ChapterReviewService chapterReviewService;

    private final AiReviewTaskService aiReviewTaskService;

    private final ObjectMapper objectMapper;

    /** SSE 专用线程池，避免阻塞 Servlet 线程或占用 commonPool。按名称注入而非按类型：Executor 类型的 Bean 不止一个 */
    private final java.util.concurrent.Executor sseExecutor;

    /** 流式收尾结果埋点：开流即返回 200，成功与失败在 HTTP 层无法区分，需单独记录 */
    private final BusinessMetrics businessMetrics;

    /** 单一构造器，Spring 自动使用其注入。此处必须保留 @Qualifier：这是全项目唯一按名称注入的位置 */
    public AiController(AiService aiService, AiConfigService aiConfigService,
                        ChapterReviewService chapterReviewService,
                        AiReviewTaskService aiReviewTaskService,
                        ObjectMapper objectMapper,
                        BusinessMetrics businessMetrics,
                        @Qualifier("sseExecutor") java.util.concurrent.Executor sseExecutor) {
        this.aiService = aiService;
        this.aiConfigService = aiConfigService;
        this.chapterReviewService = chapterReviewService;
        this.aiReviewTaskService = aiReviewTaskService;
        this.objectMapper = objectMapper;
        this.businessMetrics = businessMetrics;
        this.sseExecutor = sseExecutor;
    }

    /**
     * SSE 连接超时。**必须赋值**：0 表示永不超时（见 generateStream 中的说明）。
     *
     * <p>与写作接口共用同一配置项（原先两个 controller 各自硬编码 5 分钟）：
     * {@code app.sse.timeout-ms}，默认 5 分钟。设置初始值是为了单测：
     * 没有 Spring 上下文时，无初始值的 {@code @Value} 为 0，而 0 即永不超时。
     */
    @Value("${app.sse.timeout-ms:300000}")
    private long sseTimeoutMs = 300_000L;

    /** 客户端已断开，收尾失败无影响（再次抛出只会填满日志） */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端已断开，无可用操作
        }
    }

    @Operation(summary = "AI 生成（书名/简介）")
    @PostMapping("/generate")
    @RateLimit(name = "ai:generate", limit = 20)
    public ResponseDTO<String> generate(@Valid @RequestBody AiGenerateForm form) {
        return ResponseDTO.ok(aiService.generate(form.getType(), form.getInput()));
    }

    @Operation(summary = "AI 生成（流式 SSE，打字机效果）")
    @GetMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(name = "ai:generate", limit = 20)
    public SseEmitter generateStream(@RequestParam String type, @RequestParam String input,
                                     HttpServletResponse response) {
        // 创建 emitter 前同步解析配置并扣额度：额度耗尽 / 无 Key 时抛出 BusinessException，
        // 由全局异常处理返回 400 JSON，前端 fetch 才能取得非 200 状态并提示，
        // 避免返回已为 200 的 SSE 半开连接。
        // userId 必须在此处获取：下方异步线程中没有登录上下文，无法获取。
        Long userId = LoginUserUtil.getUserId();
        // 按本次送入模型的字数扣额度（起名/简介这类短文本按最小计费单位）：
        // 提示词位于 AiService，因此估算也由其提供。
        int units = aiService.estimateUnits(type, input);
        // 扣费字数写入响应头，与续写/润色使用**同一个头名**（见 SseConstant）。
        // 此处原先遗漏：同为「流式 AI 调用」，续写/润色可读到该头，起名/简介读到空值，
        // 前端会静默显示 0 且不报错（冒烟脚本发现）。此时响应尚未提交，设置响应头有效。
        response.setHeader(SseConstant.HEADER_CHARGED_UNITS, String.valueOf(units));
        AiConfig config = aiConfigService.getActiveConfigForUser(userId, units);
        // 开流前先判定 AI 是否可用（续写/润色早已如此处理）。
        // 若不判定：失败发生在**异步块**中，用户侧仍会得到 400 与提示文案（异步错误处理
        // 会将响应改写为 400），但日志中会多出一条带堆栈的 ERROR，使排查者误认为发生真实故障。
        aiService.requireGenerateReady(config);
        // 必须设置超时。原实现写 0L 即永不超时：客户端断开、或生成线程真正阻塞时，
        // emitter 会一直保留在容器中占用连接与线程，没有任何兜底回收机制。
        // 单次生成为秒级，5 分钟已足够宽松。
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        // 在专用线程池中执行流式生成，避免阻塞 Servlet 线程
        // （线程池满时的兜底行为见 config/AsyncConfig 的类注释）
        CompletableFuture.runAsync(() -> {
            try {
                aiService.generateStream(type, input, config, chunk -> {
                    try {
                        // 每段内容先编码为 JSON 再发送：帧内 data: 之后不能出现真实换行，
                        // 否则一帧会被拆为两帧、后半部分被丢弃（正文中的段落换行即由此丢失）
                        emitter.send(SseEmitter.event().data(SsePayload.encode(objectMapper, chunk)));
                    } catch (IOException e) {
                        // 客户端已断开。**必须抛出异常**：若仅返回，模型侧会把剩余内容全部生成完毕，
                        // 额外消耗输出 token 并继续占用该线程，而用户端早已断开
                        throw new StreamCancelledException("客户端已断开，停止生成", e);
                    } catch (IllegalStateException e) {
                        // emitter 已结束，最常见的来源是 **SSE 超时**：
                        // 容器回收连接，Spring 将 emitter 标记为 completed，下一次 send 即抛出
                        // 「ResponseBodyEmitter has already completed」。
                        // 关键是使其进入**同一条** StreamCancelledException 通道：否则
                        // 会被 chatStream 包装为「调用 AI 接口失败，请检查 Key 与网络」，
                        // 该 ERROR 会将排查方向引向错误处（曾出现超时却长时间排查 Key）。
                        // 不依据 message 文本判断（文本会随国际化变化），仅依据类型：在 send 中抛出
                        // IllegalStateException 的实际来源只有 emitter 状态。
                        throw new StreamCancelledException("流已结束（超时），停止生成", e, true);
                    }
                });
                emitter.complete();
                businessMetrics.sseStream("generate", "done");
            } catch (StreamCancelledException e) {
                // 是否退回额度取决于原因（该语义由异常携带）：
                //   主动停止 → **不退回**（界面已注明「额度不会退回」，且成本确已产生）；
                //   流超时 → **退回**（用户等待五分钟未取得任何内容，按「失败退回」处理）
                if (e.isRefundable()) {
                    businessMetrics.sseStream("generate", "timeout");
                    aiConfigService.refundQuotaIfCharged(config, userId);
                    log.warn("生成中断，已归还本次额度：{}", e.getMessage());
                } else {
                    businessMetrics.sseStream("generate", "cancelled");
                    log.info("本次生成已停止：{}", e.getMessage());
                }
                completeQuietly(emitter);
            } catch (Exception e) {
                // 流式生成失败同样需退回已扣额度，否则用户需为一次失败付费
                businessMetrics.sseStream("generate", "error");
                aiConfigService.refundQuotaIfCharged(config, userId);
                log.error("流式生成失败", e);
                emitter.completeWithError(e);
            }
        }, sseExecutor);
        return emitter;
    }

    @Operation(summary = "AI 审查章节（错别字 / 语病 / 标点 / 前后不一致，按正文字数扣免费额度）")
    @PostMapping("/review/chapter/{chapterId}")
    @RateLimit(name = "ai:review", limit = 10)
    public ResponseDTO<ChapterReviewVO> reviewChapter(@PathVariable Long chapterId) {
        return ResponseDTO.ok(chapterReviewService.reviewChapter(chapterId));
    }

    // ==================== 全文审查（阶段 5） ====================
    //
    // 与上方的单章审查属于两条路径，不共用接口：单章为「点击后等待数秒」，全书为
    // 「派发任务、轮询进度、按章查看结果」。若合并为一个接口，前端需依赖
    // 「是否返回进度字段」来推断本次调用是同步还是异步。

    @Operation(summary = "全文审查：入口数据（最近一次任务 + 本书规模 + 今日剩余字数）")
    @GetMapping("/review/novel/{novelId}/overview")
    public ResponseDTO<AiReviewOverviewVO> reviewOverview(@PathVariable Long novelId) {
        return ResponseDTO.ok(aiReviewTaskService.overview(novelId));
    }

    @Operation(summary = "全文审查：发起（可指定范围：整本 / 最近 N 章 / 章号区间；"
            + "已有进行中的任务时直接返回该任务，不会重复扣费）")
    @PostMapping("/review/novel/{novelId}")
    @RateLimit(name = "ai:review", limit = 10)
    public ResponseDTO<AiReviewTaskVO> reviewNovel(@PathVariable Long novelId,
                                                   @RequestBody(required = false) AiReviewStartForm form) {
        return ResponseDTO.ok(aiReviewTaskService.start(novelId, form));
    }

    @Operation(summary = "全文审查：查询任务进度（页面轮询）")
    @GetMapping("/review/task/{taskId}")
    public ResponseDTO<AiReviewTaskVO> reviewTask(@PathVariable Long taskId) {
        return ResponseDTO.ok(aiReviewTaskService.detail(taskId));
    }

    @Operation(summary = "全文审查：问题清单（分页，同一问题出现在多章时只占一行）")
    @GetMapping("/review/task/{taskId}/issues")
    public ResponseDTO<PageResult<AiReviewIssueVO>> reviewIssues(@PathVariable Long taskId,
                                                                 @RequestParam(defaultValue = "1") long pageNum,
                                                                 @RequestParam(defaultValue = "20") long pageSize) {
        return ResponseDTO.ok(aiReviewTaskService.pageIssues(taskId, pageNum, pageSize));
    }

    @Operation(summary = "全文审查：继续审查（只补还没审成的章，额度用完后明天从这里接着审）")
    @PostMapping("/review/task/{taskId}/resume")
    @RateLimit(name = "ai:review", limit = 10)
    public ResponseDTO<AiReviewTaskVO> resumeReview(@PathVariable Long taskId) {
        return ResponseDTO.ok(aiReviewTaskService.resume(taskId));
    }

    @Operation(summary = "全文审查：取消任务（已审过的章节与结果保留）")
    @PostMapping("/review/task/{taskId}/cancel")
    public ResponseDTO<Void> cancelReview(@PathVariable Long taskId) {
        aiReviewTaskService.cancel(taskId);
        return ResponseDTO.ok();
    }

    /**
     * 手动重跑 AI 预审（运维用）
     *
     * <p>作品正常发布链路为「置为待审 + 自动投递审核消息」，无需调用该接口；
     * 该接口用于「审核消息丢失 / 需对存量作品补跑一次」的兜底场景，
     * 因此**没有前端调用方属正常**，页面中不放按钮。
     *
     * <p>**必须使用 {@code @SaCheckRole("admin")}**：全局拦截器只执行
     * {@code checkLogin}，而下方操作是「将任意 {@code novelId} 的审核状态改为待审(0)」，
     * 待审**不在** {@code NovelVisibility.VISIBLE_AUDIT_STATUSES} 中，即读者不可见。
     * 缺少角色约束时，任何登录用户都可将他人作品从读者视野中移除，并额外消耗一次平台 AI 调用。
     * {@code SearchController} 的 {@code /reindex}、{@code /vector-reindex} 曾出现同一问题
     * 并已注明，此处此前遗漏。
     */
    @Operation(summary = "手动重跑 AI 预审（管理员 / 运维用，正常发布链路不需要）")
    @SaCheckRole("admin")
    @PostMapping("/audit/{novelId}")
    public ResponseDTO<Void> submitAudit(@PathVariable Long novelId) {
        aiService.submitAudit(novelId);
        return ResponseDTO.ok();
    }

    @Operation(summary = "查询 AI 配置（Key 脱敏，管理员）")
    @SaCheckRole("admin")
    @GetMapping("/config")
    public ResponseDTO<AiConfig> getConfig() {
        return ResponseDTO.ok(aiConfigService.getConfigMasked());
    }

    @Operation(summary = "保存 AI 配置（管理员）")
    @SaCheckRole("admin")
    @PostMapping("/config")
    public ResponseDTO<Void> saveConfig(@RequestBody AiConfigForm form) {
        aiConfigService.saveConfig(form);
        return ResponseDTO.ok();
    }

    @Operation(summary = "查询当前用户 AI 配置（自带 Key 脱敏 + 免费额度）")
    @GetMapping("/my-config")
    public ResponseDTO<UserAiConfigVO> getMyConfig() {
        return ResponseDTO.ok(aiConfigService.getUserConfigMasked(LoginUserUtil.getUserId()));
    }

    @Operation(summary = "保存当前用户自带 AI 配置（Key 脱敏值=未修改、空串=清除；地址/模型留空则沿用平台）")
    @PostMapping("/my-key")
    public ResponseDTO<Void> saveMyKey(@RequestBody UserAiKeyForm form) {
        aiConfigService.saveUserKey(LoginUserUtil.getUserId(), form);
        return ResponseDTO.ok();
    }
}
