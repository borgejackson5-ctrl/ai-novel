package com.ainovel.module.bookshelf.controller;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.bookshelf.domain.vo.BookshelfVO;
import com.ainovel.module.bookshelf.service.BookshelfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 书架接口（全局登录拦截，无需注解）
 */
@Tag(name = "书架")
@RestController
@RequestMapping("/bookshelf")
@RequiredArgsConstructor
public class BookshelfController {

    private final BookshelfService bookshelfService;

    @Operation(summary = "我的书架分页（keyword 按书名模糊匹配）")
    @GetMapping("/list")
    public ResponseDTO<PageResult<BookshelfVO>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "12") int pageSize,
            @RequestParam(required = false) String keyword) {
        return ResponseDTO.ok(bookshelfService.page(pageNum, pageSize, keyword));
    }

    @Operation(summary = "加入书架")
    @PostMapping("/{novelId}")
    @RateLimit(name = "bookshelf:write", limit = 30)
    public ResponseDTO<Void> add(@PathVariable Long novelId) {
        bookshelfService.add(novelId);
        return ResponseDTO.ok();
    }

    @Operation(summary = "移出书架")
    @DeleteMapping("/{novelId}")
    @RateLimit(name = "bookshelf:write", limit = 30)
    public ResponseDTO<Void> remove(@PathVariable Long novelId) {
        bookshelfService.remove(novelId);
        return ResponseDTO.ok();
    }

    @Operation(summary = "是否已收藏")
    @GetMapping("/{novelId}/status")
    public ResponseDTO<Boolean> contains(@PathVariable Long novelId) {
        return ResponseDTO.ok(bookshelfService.contains(novelId));
    }
}
