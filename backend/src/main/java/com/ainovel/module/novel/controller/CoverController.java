package com.ainovel.module.novel.controller;

import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.novel.domain.form.CoverGenerateForm;
import com.ainovel.module.novel.service.CoverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 封面接口：AI 生成 / 用户上传，返回 OSS 永久公开 URL
 *
 * <p>需登录（由全局 Sa-Token 拦截器兜底），供创作发布页调用。
 */
@Tag(name = "封面")
@RestController
@RequestMapping("/cover")
@RequiredArgsConstructor
public class CoverController {

    private final CoverService coverService;

    @Operation(summary = "AI 生成封面（文生图）")
    @PostMapping("/generate")
    public ResponseDTO<String> generate(@Valid @RequestBody CoverGenerateForm form) {
        return ResponseDTO.ok(coverService.generate(form.getPrompt()));
    }

    @Operation(summary = "上传封面图片")
    @PostMapping("/upload")
    public ResponseDTO<String> upload(@RequestParam("file") MultipartFile file) {
        return ResponseDTO.ok(coverService.upload(file));
    }
}
