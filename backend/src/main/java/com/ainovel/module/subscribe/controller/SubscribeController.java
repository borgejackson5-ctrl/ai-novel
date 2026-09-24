package com.ainovel.module.subscribe.controller;

import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.subscribe.domain.entity.SubscribeOrder;
import com.ainovel.module.subscribe.domain.form.SubscribeForm;
import com.ainovel.module.subscribe.domain.vo.UnlockStatusVO;
import com.ainovel.module.subscribe.service.SubscribeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.ainovel.common.annotation.Idempotent;

/**
 * 订阅/解锁接口
 */
@Tag(name = "订阅解锁")
@RestController
@RequestMapping("/subscribe")
@RequiredArgsConstructor
public class SubscribeController {

    private final SubscribeService subscribeService;

    @Operation(summary = "解锁章节/整本")
    @PostMapping("/unlock")
    @Idempotent
    public ResponseDTO<SubscribeOrder> unlock(@Valid @RequestBody SubscribeForm form) {
        Long userId = LoginUserUtil.getUserId();
        return ResponseDTO.ok(subscribeService.unlock(userId, form.getNovelId(), form.getChapterId()));
    }

    @Operation(summary = "是否已解锁")
    @GetMapping("/check/{novelId}")
    public ResponseDTO<Boolean> check(@PathVariable Long novelId,
                                      @RequestParam(required = false) Long chapterId) {
        Long userId = LoginUserUtil.getUserId();
        return ResponseDTO.ok(subscribeService.canRead(userId, novelId, chapterId));
    }

    @Operation(summary = "查询某本书的解锁状态（整本 + 已解锁章节，批量返回避免逐章 N+1）")
    @GetMapping("/unlock-status/{novelId}")
    public ResponseDTO<UnlockStatusVO> unlockStatus(@PathVariable Long novelId) {
        Long userId = LoginUserUtil.getUserId();
        return ResponseDTO.ok(subscribeService.unlockStatus(userId, novelId));
    }
}
