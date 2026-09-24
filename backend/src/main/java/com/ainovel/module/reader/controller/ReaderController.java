package com.ainovel.module.reader.controller;

import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.reader.domain.form.PreferenceForm;
import com.ainovel.module.reader.domain.form.ProgressForm;
import com.ainovel.module.reader.domain.vo.PreferenceVO;
import com.ainovel.module.reader.domain.vo.ProgressVO;
import com.ainovel.module.reader.service.ReaderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 阅读进度 / 阅读偏好接口（全局 Sa-Token 已做登录拦截，无需额外权限注解）
 */
@Tag(name = "阅读")
@RestController
@RequestMapping("/reader")
@RequiredArgsConstructor
public class ReaderController {

    private final ReaderService readerService;

    @Operation(summary = "获取继续阅读进度")
    @GetMapping("/progress")
    public ResponseDTO<ProgressVO> progress() {
        return ResponseDTO.ok(readerService.getProgress());
    }

    @Operation(summary = "保存阅读进度")
    @PutMapping("/progress")
    @RateLimit(name = "reader:progress", limit = 60)
    public ResponseDTO<Void> saveProgress(@Valid @RequestBody ProgressForm form) {
        readerService.saveProgress(form);
        return ResponseDTO.ok();
    }

    @Operation(summary = "获取阅读偏好")
    @GetMapping("/preference")
    public ResponseDTO<PreferenceVO> preference() {
        return ResponseDTO.ok(readerService.getPreference());
    }

    @Operation(summary = "保存阅读偏好")
    @PutMapping("/preference")
    @RateLimit(name = "reader:preference", limit = 30)
    public ResponseDTO<Void> savePreference(@Valid @RequestBody PreferenceForm form) {
        readerService.savePreference(form);
        return ResponseDTO.ok();
    }
}
