package com.ainovel.module.admin.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ainovel.common.annotation.AdminLogRecord;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.admin.domain.form.CategoryForm;
import com.ainovel.module.admin.domain.vo.CategoryAdminVO;
import com.ainovel.module.admin.service.AdminCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理后台接口：分类字典维护
 *
 * <p>分类为低频变更的字典数据，但每次变更影响整站：前台分类导航、发布与审核时的下拉、
 * 书封配色（前端按分类 id 取色）。因此三个写操作均记录操作日志，删除还需通过
 * 「分类下无作品」的校验。
 *
 * <p>对外仅提供启用 / 禁用，而非物理删除：禁用仅从导航中隐藏，
 * 已有作品与链接仍然有效，可随时恢复。
 */
@Tag(name = "管理后台 - 分类")
@SaCheckRole("admin")
@RestController
@RequestMapping("/admin/category")
@RequiredArgsConstructor
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    @Operation(summary = "分类列表（含禁用，带作品数）")
    @GetMapping("/list")
    public ResponseDTO<List<CategoryAdminVO>> list() {
        return ResponseDTO.ok(adminCategoryService.list());
    }

    @AdminLogRecord(module = "CATEGORY", action = "SAVE", targetType = "CATEGORY", targetIdIndex = -1,
            summary = "新增分类", detail = "#p0.name")
    @Operation(summary = "新增分类")
    @PostMapping
    public ResponseDTO<Void> create(@Valid @RequestBody CategoryForm form) {
        adminCategoryService.create(form);
        return ResponseDTO.ok();
    }

    @AdminLogRecord(module = "CATEGORY", action = "SAVE", targetType = "CATEGORY",
            summary = "修改分类", detail = "#p1.name")
    @Operation(summary = "修改分类（含启用/禁用）")
    @PutMapping("/{id}")
    public ResponseDTO<Void> update(@PathVariable Long id, @Valid @RequestBody CategoryForm form) {
        adminCategoryService.update(id, form);
        return ResponseDTO.ok();
    }

    @AdminLogRecord(module = "CATEGORY", action = "DELETE", targetType = "CATEGORY",
            summary = "删除分类", detail = "#p0")
    @Operation(summary = "删除分类（分类下有作品时拒绝）")
    @DeleteMapping("/{id}")
    public ResponseDTO<Void> delete(@PathVariable Long id) {
        adminCategoryService.delete(id);
        return ResponseDTO.ok();
    }
}
