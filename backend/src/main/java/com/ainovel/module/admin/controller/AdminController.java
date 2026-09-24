package com.ainovel.module.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ainovel.common.annotation.AdminLogRecord;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.admin.domain.form.AuditRejectForm;
import com.ainovel.module.admin.domain.vo.AdminLogVO;
import com.ainovel.module.admin.domain.vo.DashboardVO;
import com.ainovel.module.admin.domain.vo.NovelAuditVO;
import com.ainovel.module.admin.service.AdminLogService;
import com.ainovel.module.admin.service.AdminService;
import com.ainovel.module.ai.service.AiConfigService;
import com.ainovel.module.coin.domain.vo.RechargeOrderVO;
import com.ainovel.module.feedback.domain.form.FeedbackHandleForm;
import com.ainovel.module.feedback.domain.vo.FeedbackVO;
import com.ainovel.module.feedback.service.FeedbackService;
import com.ainovel.module.novel.domain.form.AppealHandleForm;
import com.ainovel.module.novel.domain.vo.ChapterAuditVO;
import com.ainovel.module.novel.domain.vo.ImportResultVO;
import com.ainovel.module.novel.domain.vo.NovelAppealVO;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.monitor.domain.vo.SystemMetricsVO;
import com.ainovel.module.monitor.service.SystemMetricsService;
import com.ainovel.module.novel.service.NovelAppealService;
import com.ainovel.module.novel.service.NovelImportService;
import com.ainovel.module.search.domain.vo.ReconcileResultVO;
import com.ainovel.module.search.service.SearchReconcileService;
import com.ainovel.module.subscribe.domain.vo.SubscribeOrderVO;
import com.ainovel.module.user.domain.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 管理后台接口：用户管理 + 订单管理
 *
 * <p>类级别 @SaCheckRole("admin")，所有接口仅超级管理员可访问。
 * 小说管理复用 NovelController 现有 CRUD。
 */
@Tag(name = "管理后台")
@SaCheckRole("admin")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    private final AdminLogService adminLogService;

    private final NovelImportService novelImportService;

    private final FeedbackService feedbackService;

    private final AiConfigService aiConfigService;

    private final NovelAppealService novelAppealService;

    private final SearchReconcileService searchReconcileService;

    private final SystemMetricsService systemMetricsService;

    @Operation(summary = "搜索索引对账（比对 DB 与索引，发现漂移自动修复）")
    @PostMapping("/search/reconcile")
    public ResponseDTO<ReconcileResultVO> reconcileSearchIndex() {
        return ResponseDTO.ok(searchReconcileService.reconcile());
    }

    @Operation(summary = "系统健康：接口耗时统计 + 慢 SQL TopN + 慢请求记录")
    @GetMapping("/system/metrics")
    public ResponseDTO<SystemMetricsVO> systemMetrics() {
        return ResponseDTO.ok(systemMetricsService.metrics());
    }

    @Operation(summary = "重置运行指标统计基线")
    @PostMapping("/system/metrics/reset")
    public ResponseDTO<Void> resetSystemMetrics() {
        systemMetricsService.reset();
        return ResponseDTO.ok();
    }

    @Operation(summary = "数据看板聚合")
    @GetMapping("/dashboard")
    public ResponseDTO<DashboardVO> dashboard() {
        return ResponseDTO.ok(adminService.dashboard());
    }

    @Operation(summary = "作品列表分页（管理端专用：含未过审 / 已下架，status/auditStatus 不传即全部）")
    @GetMapping("/novel/page")
    public ResponseDTO<PageResult<NovelVO>> novelPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "30") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer auditStatus) {
        return ResponseDTO.ok(adminService.novelPage(pageNum, pageSize, keyword, categoryId, status, auditStatus));
    }

    @Operation(summary = "作品审核分页（auditStatus：0待审/1通过/2拒绝/3变更待审，不传查全部）")
    @GetMapping("/audit/page")
    public ResponseDTO<PageResult<NovelAuditVO>> auditPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer auditStatus) {
        return ResponseDTO.ok(adminService.auditPage(pageNum, pageSize, auditStatus));
    }

    @Operation(summary = "待人工终审数量")
    @GetMapping("/audit/pending-count")
    public ResponseDTO<Long> pendingAuditCount() {
        return ResponseDTO.ok(adminService.pendingAuditCount());
    }

    @AdminLogRecord(module = "AUDIT", action = "PASS", targetType = "NOVEL",
            summary = "审核通过作品", detail = "#p1 == null ? \"分类未修正\" : \"修正分类ID=\" + #p1")
    @Operation(summary = "审核通过（可修正分类，通过即自动上架）")
    @PostMapping("/audit/{id}/pass")
    public ResponseDTO<Void> auditPass(@PathVariable Long id,
                                       @RequestParam(required = false) Long categoryId) {
        adminService.auditPass(id, categoryId);
        return ResponseDTO.ok();
    }

    @AdminLogRecord(module = "AUDIT", action = "REJECT", targetType = "NOVEL",
            summary = "审核拒绝作品", detail = "#p1.reason")
    @Operation(summary = "审核拒绝（理由必填）")
    @PostMapping("/audit/{id}/reject")
    public ResponseDTO<Void> auditReject(@PathVariable Long id,
                                         @Valid @RequestBody AuditRejectForm form) {
        adminService.auditReject(id, form.getReason());
        return ResponseDTO.ok();
    }

    @Operation(summary = "章节审核分页（auditStatus：0待审/3变更待审，不传查两者）")
    @GetMapping("/audit/chapter/page")
    public ResponseDTO<PageResult<ChapterAuditVO>> auditChapterPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer auditStatus) {
        return ResponseDTO.ok(adminService.auditChapterPage(pageNum, pageSize, auditStatus));
    }

    @AdminLogRecord(module = "AUDIT", action = "PASS", targetType = "CHAPTER",
            summary = "审核通过章节")
    @Operation(summary = "章节审核通过（新章上架 / 变更章影子正文原子替换）")
    @PostMapping("/audit/chapter/{id}/pass")
    public ResponseDTO<Void> auditChapterPass(@PathVariable Long id) {
        adminService.auditChapterPass(id);
        return ResponseDTO.ok();
    }

    @AdminLogRecord(module = "AUDIT", action = "REJECT", targetType = "CHAPTER",
            summary = "审核拒绝章节", detail = "#p1.reason")
    @Operation(summary = "章节审核拒绝（理由必填）")
    @PostMapping("/audit/chapter/{id}/reject")
    public ResponseDTO<Void> auditChapterReject(@PathVariable Long id,
                                                @Valid @RequestBody AuditRejectForm form) {
        adminService.auditChapterReject(id, form.getReason());
        return ResponseDTO.ok();
    }

    @AdminLogRecord(module = "IMPORT", action = "IMPORT", targetType = "NOVEL", targetIdIndex = -1,
            summary = "导入公版书 TXT", detail = "#p2")
    @Operation(summary = "导入公版书 TXT（自动识别编码与分章，按书名去重，可覆盖重建，可选整本免费）")
    @PostMapping("/import")
    public ResponseDTO<ImportResultVO> importNovel(
            @RequestParam("file") MultipartFile file,
            @RequestParam Long categoryId,
            @RequestParam String title,
            @RequestParam(required = false) String author,
            @RequestParam(defaultValue = "false") boolean overwrite,
            @RequestParam(defaultValue = "false") boolean free) {
        return ResponseDTO.ok(novelImportService.importTxt(file, categoryId, title, author, overwrite, free));
    }

    @Operation(summary = "用户分页")
    @GetMapping("/user/page")
    public ResponseDTO<PageResult<UserVO>> userPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword) {
        return ResponseDTO.ok(adminService.userPage(pageNum, pageSize, keyword));
    }

    @AdminLogRecord(module = "USER", action = "STATUS", targetType = "USER",
            summary = "变更用户状态", detail = "#p1 == 1 ? \"启用\" : \"禁用\"")
    @Operation(summary = "禁用/启用用户")
    @PutMapping("/user/{id}/status")
    public ResponseDTO<Void> updateUserStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminService.updateUserStatus(id, status);
        return ResponseDTO.ok();
    }

    @Operation(summary = "充值订单分页")
    @GetMapping("/order/recharge/page")
    public ResponseDTO<PageResult<RechargeOrderVO>> rechargeOrderPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return ResponseDTO.ok(adminService.rechargeOrderPage(pageNum, pageSize, status, keyword));
    }

    @Operation(summary = "解锁订单分页")
    @GetMapping("/order/subscribe/page")
    public ResponseDTO<PageResult<SubscribeOrderVO>> subscribeOrderPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return ResponseDTO.ok(adminService.subscribeOrderPage(pageNum, pageSize, status, keyword));
    }

    @Operation(summary = "用户反馈分页（status：0待处理/1已采纳/2未采纳，type 可选）")
    @GetMapping("/feedback/page")
    public ResponseDTO<PageResult<FeedbackVO>> feedbackPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String type) {
        return ResponseDTO.ok(feedbackService.adminPage(pageNum, pageSize, status, type));
    }

    @Operation(summary = "待处理反馈数（侧栏角标用）")
    @GetMapping("/feedback/pending-count")
    public ResponseDTO<Long> feedbackPendingCount() {
        return ResponseDTO.ok(feedbackService.pendingCount());
    }

    @AdminLogRecord(module = "FEEDBACK", action = "HANDLE", targetType = "FEEDBACK",
            summary = "处理用户反馈",
            detail = "(#p1.status == 1 ? '已采纳' : '未采纳')"
                    + " + (#p1.rewardCoin != null && #p1.rewardCoin > 0 ? '，奖励 ' + #p1.rewardCoin : '')"
                    + " + (#p1.reply != null && !#p1.reply.isEmpty() ? '，回复：' + #p1.reply : '')")
    @Operation(summary = "处理反馈（状态 + 回复 + 采纳奖励虚拟币）")
    @PutMapping("/feedback/{id}/handle")
    public ResponseDTO<Void> handleFeedback(@PathVariable Long id,
                                            @Valid @RequestBody FeedbackHandleForm form) {
        feedbackService.handle(id, form);
        return ResponseDTO.ok();
    }

    @Operation(summary = "申请工单分页（status：0待处理/1已通过/2已驳回，不传查全部）")
    @GetMapping("/appeal/page")
    public ResponseDTO<PageResult<NovelAppealVO>> appealPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer status) {
        return ResponseDTO.ok(novelAppealService.adminPage(pageNum, pageSize, status));
    }

    @AdminLogRecord(module = "APPEAL", action = "HANDLE", targetType = "NOVEL_APPEAL",
            summary = "处理作品申请", detail = "#p1.approved ? \"已通过\" : \"已驳回\"")
    @Operation(summary = "处理申请工单（批准则执行对应业务动作，如恢复连载）")
    @PutMapping("/appeal/{id}/handle")
    public ResponseDTO<Void> handleAppeal(@PathVariable Long id,
                                          @Valid @RequestBody AppealHandleForm form) {
        novelAppealService.handle(id, form);
        return ResponseDTO.ok();
    }

    @AdminLogRecord(module = "AI", action = "RESET", targetType = "AI_QUOTA",
            summary = "重置用户 AI 免费额度")
    @Operation(summary = "重置某用户的 AI 免费额度")
    @PostMapping("/ai-quota/{userId}/reset")
    public ResponseDTO<Void> resetAiQuota(@PathVariable Long userId) {
        aiConfigService.resetUserQuota(userId);
        return ResponseDTO.ok();
    }

    @Operation(summary = "操作日志分页（审计追溯：谁在何时对哪条内容做了什么）")
    @GetMapping("/log/page")
    public ResponseDTO<PageResult<AdminLogVO>> logPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "30") int pageSize,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long adminId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "false") boolean onlyFailed) {
        return ResponseDTO.ok(adminLogService.page(pageNum, pageSize, module, action,
                adminId, keyword, startDate, endDate, onlyFailed));
    }
}
