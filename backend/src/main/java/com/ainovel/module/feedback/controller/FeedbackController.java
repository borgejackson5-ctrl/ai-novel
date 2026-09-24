package com.ainovel.module.feedback.controller;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.feedback.domain.form.FeedbackForm;
import com.ainovel.module.feedback.domain.vo.FeedbackVO;
import com.ainovel.module.feedback.service.FeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户反馈接口
 */
@Tag(name = "用户反馈")
@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "提交反馈（type: FEELING/SUGGESTION/BUG）")
    @PostMapping
    public ResponseDTO<Void> submit(@Valid @RequestBody FeedbackForm form) {
        feedbackService.submit(form);
        return ResponseDTO.ok();
    }

    @Operation(summary = "我的反馈分页")
    @GetMapping("/my")
    public ResponseDTO<PageResult<FeedbackVO>> my(@RequestParam(defaultValue = "1") int pageNum,
                                                  @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseDTO.ok(feedbackService.pageMine(pageNum, pageSize));
    }
}
