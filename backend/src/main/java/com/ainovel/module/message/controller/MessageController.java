package com.ainovel.module.message.controller;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.message.domain.vo.MessageVO;
import com.ainovel.module.message.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 站内信接口（全局登录拦截，无需注解）
 */
@Tag(name = "站内信")
@RestController
@RequestMapping("/message")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @Operation(summary = "我的消息分页（isRead 可选：0 未读 / 1 已读）")
    @GetMapping("/page")
    public ResponseDTO<PageResult<MessageVO>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer isRead) {
        return ResponseDTO.ok(messageService.page(pageNum, pageSize, isRead));
    }

    @Operation(summary = "未读数")
    @GetMapping("/unread-count")
    @RateLimit(name = "message:unread", limit = 120)
    public ResponseDTO<Long> unreadCount() {
        return ResponseDTO.ok(messageService.unreadCount());
    }

    @Operation(summary = "标记单条已读")
    @PutMapping("/read/{id}")
    public ResponseDTO<Void> markRead(@PathVariable Long id) {
        messageService.markRead(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "全部已读")
    @PutMapping("/read-all")
    public ResponseDTO<Void> markAllRead() {
        messageService.markAllRead();
        return ResponseDTO.ok();
    }

    @Operation(summary = "清空已读消息（只删已读，未读不动；返回清掉的条数）")
    @DeleteMapping("/read")
    public ResponseDTO<Integer> clearRead() {
        return ResponseDTO.ok(messageService.clearRead());
    }
}
