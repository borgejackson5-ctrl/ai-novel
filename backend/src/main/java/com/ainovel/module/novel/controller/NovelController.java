package com.ainovel.module.novel.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.ainovel.common.annotation.AdminLogRecord;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.novel.domain.form.NovelEditForm;
import com.ainovel.module.novel.domain.form.NovelForm;
import com.ainovel.module.novel.domain.form.NovelPublishForm;
import com.ainovel.module.novel.domain.form.NovelQueryForm;
import com.ainovel.module.novel.domain.form.ResumeSerialForm;
import com.ainovel.module.novel.domain.vo.LikeVO;
import com.ainovel.module.novel.domain.vo.NovelEditVO;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.novel.domain.vo.NovelStatsVO;
import com.ainovel.module.novel.domain.vo.ParsedChapterVO;
import com.ainovel.module.novel.service.NovelImportService;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.novel.service.NovelStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 小说接口
 */
@Tag(name = "小说")
@RestController
@RequestMapping("/novel")
@RequiredArgsConstructor
public class NovelController {

    private final NovelService novelService;

    private final NovelImportService novelImportService;

    private final NovelStatsService novelStatsService;

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    @RateLimit(name = "novel:list", limit = 120)
    public ResponseDTO<PageResult<NovelVO>> page(NovelQueryForm form) {
        return ResponseDTO.ok(novelService.page(form));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    @RateLimit(name = "novel:detail", limit = 120)
    public ResponseDTO<NovelVO> detail(@PathVariable Long id) {
        return ResponseDTO.ok(novelService.detail(id));
    }

    @Operation(summary = "发布作品（用户）")
    @PostMapping("/publish")
    public ResponseDTO<NovelVO> publish(@Valid @RequestBody NovelPublishForm form) {
        return ResponseDTO.ok(novelService.publish(form));
    }

    @Operation(summary = "解析上传的 TXT（用户端投稿预览，只解析不落库）")
    @PostMapping("/import-parse")
    public ResponseDTO<List<ParsedChapterVO>> importParse(@RequestParam("file") MultipartFile file) {
        return ResponseDTO.ok(novelImportService.parseTxt(file));
    }

    @Operation(summary = "我的作品分页")
    @GetMapping("/mine")
    public ResponseDTO<PageResult<NovelVO>> mine(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize) {
        return ResponseDTO.ok(novelService.mine(pageNum, pageSize));
    }

    @Operation(summary = "某位作者的公开发布作品（作者主页，仅读者可见的）")
    @GetMapping("/by-author/{authorId}")
    @RateLimit(name = "novel:list", limit = 120)
    public ResponseDTO<PageResult<NovelVO>> byAuthor(
            @PathVariable Long authorId,
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "12") long pageSize) {
        return ResponseDTO.ok(novelService.byAuthor(authorId, pageNum, pageSize));
    }

    @Operation(summary = "作者编辑作品：取当前值与待审值（仅作品所有者）")
    @GetMapping("/mine/{id}")
    public ResponseDTO<NovelEditVO> editDetail(@PathVariable Long id) {
        return ResponseDTO.ok(novelService.editDetail(id));
    }

    @Operation(summary = "作者提交作品信息变更（走变更送审，审核期间前台仍显示旧值）")
    @PutMapping("/mine/{id}")
    public ResponseDTO<NovelEditVO> submitEdit(@PathVariable Long id,
                                               @Valid @RequestBody NovelEditForm form) {
        return ResponseDTO.ok(novelService.submitEdit(id, form));
    }

    @Operation(summary = "作者下架作品（新书保护期内不可下架；下架后读者仍可读已解锁章节）")
    @PutMapping("/mine/{id}/offline")
    public ResponseDTO<Void> offlineMine(@PathVariable Long id) {
        novelService.offlineByAuthor(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "作者申请重新上架（下架满 24 小时后可申请，需管理员复核）")
    @PostMapping("/mine/{id}/reshelve")
    public ResponseDTO<Void> reshelveMine(@PathVariable Long id) {
        novelService.requestReshelve(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "作者删除作品（需已下架满 7 天，且无读者付费解锁过）")
    @DeleteMapping("/mine/{id}")
    public ResponseDTO<Void> deleteMine(@PathVariable Long id) {
        novelService.deleteByAuthor(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "作者数据看板（阅读人数、章节曲线、流失章节、收藏数）")
    @GetMapping("/mine/{id}/stats")
    public ResponseDTO<NovelStatsVO> stats(@PathVariable Long id) {
        return ResponseDTO.ok(novelStatsService.stats(id));
    }

    @Operation(summary = "作者标记作品已完结（完结后章节与简介等内容锁定，仅可改书名/封面）")
    @PostMapping("/mine/{id}/finish")
    public ResponseDTO<Void> finishMine(@PathVariable Long id) {
        novelService.finishByAuthor(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "作者申请解除完结（需完结满 3 天，管理员批准后恢复连载）")
    @PostMapping("/mine/{id}/resume-serial")
    public ResponseDTO<Void> resumeSerial(@PathVariable Long id,
                                          @RequestBody(required = false) ResumeSerialForm form) {
        novelService.requestResumeSerial(id, form == null ? null : form.getReason());
        return ResponseDTO.ok();
    }

    @AdminLogRecord(module = "NOVEL", action = "SAVE", targetType = "NOVEL", targetIdIndex = -1,
            summary = "新增/编辑小说", detail = "#p0.title")
    @Operation(summary = "新增/编辑")
    @SaCheckPermission("novel:add")
    @PostMapping("/save")
    public ResponseDTO<NovelVO> save(@Valid @RequestBody NovelForm form) {
        return ResponseDTO.ok(novelService.save(form));
    }

    @AdminLogRecord(module = "NOVEL", action = "DELETE", targetType = "NOVEL",
            summary = "删除小说")
    @Operation(summary = "删除")
    @SaCheckPermission("novel:delete")
    @DeleteMapping("/{id}")
    public ResponseDTO<Void> delete(@PathVariable Long id) {
        novelService.delete(id);
        return ResponseDTO.ok();
    }

    @AdminLogRecord(module = "NOVEL", action = "STATUS", targetType = "NOVEL",
            summary = "小说上下架", detail = "#p1 == 1 ? \"上架\" : \"下架\"")
    @Operation(summary = "上下架")
    @SaCheckPermission("novel:audit")
    @PostMapping("/status/{id}/{status}")
    public ResponseDTO<Void> changeStatus(@PathVariable Long id, @PathVariable Integer status) {
        novelService.changeStatus(id, status);
        return ResponseDTO.ok();
    }

    @Operation(summary = "阅读量+1")
    @PostMapping("/{id}/read")
    public ResponseDTO<Void> read(@PathVariable Long id) {
        novelService.recordRead(id);
        return ResponseDTO.ok();
    }

    @Operation(summary = "点赞（toggle：一人一赞可取消）")
    @PostMapping("/{id}/like")
    public ResponseDTO<LikeVO> like(@PathVariable Long id) {
        return ResponseDTO.ok(novelService.like(id));
    }
}
