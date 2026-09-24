package com.ainovel.module.category.controller;

import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.category.domain.entity.Category;
import com.ainovel.module.category.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 分类接口
 */
@Tag(name = "分类")
@RestController
@RequestMapping("/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "分类列表")
    @GetMapping("/list")
    @RateLimit(name = "category", limit = 120)
    public ResponseDTO<List<Category>> list() {
        return ResponseDTO.ok(categoryService.listAll());
    }
}
