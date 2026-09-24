package com.ainovel.module.comment.controller;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.comment.domain.form.CommentForm;
import com.ainovel.module.comment.domain.vo.CommentVO;
import com.ainovel.module.comment.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 评论接口（全局 Sa-Token 登录拦截，无需额外注解）
 */
@Tag(name = "评论")
@RestController
@RequestMapping("/comment")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @Operation(summary = "小说书评分页")
    @GetMapping("/page/{novelId}")
    @RateLimit(name = "comment:list", limit = 120)
    public ResponseDTO<PageResult<CommentVO>> page(
            @PathVariable Long novelId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseDTO.ok(commentService.page(pageNum, pageSize, novelId));
    }

    @Operation(summary = "章节章评分页（本章说）")
    @GetMapping("/chapter/{chapterId}")
    @RateLimit(name = "comment:list", limit = 120)
    public ResponseDTO<PageResult<CommentVO>> chapterPage(
            @PathVariable Long chapterId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseDTO.ok(commentService.chapterPage(pageNum, pageSize, chapterId));
    }

    @Operation(summary = "某评论的回复列表")
    @GetMapping("/replies/{commentId}")
    @RateLimit(name = "comment:list", limit = 120)
    public ResponseDTO<List<CommentVO>> replies(@PathVariable Long commentId) {
        return ResponseDTO.ok(commentService.replies(commentId));
    }

    @Operation(summary = "发表评论 / 回复")
    @PostMapping
    @RateLimit(name = "comment:add", limit = 10)
    public ResponseDTO<CommentVO> add(@Valid @RequestBody CommentForm form) {
        return ResponseDTO.ok(commentService.add(form));
    }

    @Operation(summary = "删除自己的评论")
    @DeleteMapping("/{id}")
    public ResponseDTO<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "点赞 / 取消点赞评论（切换）")
    @PostMapping("/{id}/like")
    @RateLimit(name = "comment:like", limit = 60)
    public ResponseDTO<Boolean> like(@PathVariable Long id) {
        return ResponseDTO.ok(commentService.like(id));
    }
}
