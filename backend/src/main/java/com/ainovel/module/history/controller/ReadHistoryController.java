package com.ainovel.module.history.controller;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.history.domain.form.ReadHistoryForm;
import com.ainovel.module.history.domain.vo.ReadHistoryVO;
import com.ainovel.module.history.service.ReadHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 阅读历史接口（全局登录拦截，无需注解）
 */
@Tag(name = "阅读历史")
@RestController
@RequestMapping("/history")
@RequiredArgsConstructor
public class ReadHistoryController {

    private final ReadHistoryService readHistoryService;

    @Operation(summary = "记录一次阅读")
    @PostMapping("/record")
    @RateLimit(name = "history:record", limit = 60)
    public ResponseDTO<Void> record(@Valid @RequestBody ReadHistoryForm form) {
        readHistoryService.record(form);
        return ResponseDTO.ok();
    }

    @Operation(summary = "阅读历史分页（按小说去重；日期区间含首含尾）")
    @GetMapping("/page")
    public ResponseDTO<PageResult<ReadHistoryVO>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        // 用户选择的是日期，查询使用左闭右开区间；结束日需包含当天，故 +1 天后取当天起点
        LocalDateTime startTime = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endTime = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        return ResponseDTO.ok(readHistoryService.page(pageNum, pageSize, startTime, endTime));
    }
}
