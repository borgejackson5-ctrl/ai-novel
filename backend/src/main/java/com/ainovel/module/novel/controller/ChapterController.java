package com.ainovel.module.novel.controller;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.novel.domain.form.ChapterSaveForm;
import com.ainovel.module.novel.domain.vo.ChapterContentVO;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.module.novel.service.ChapterService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 章节接口（读者只读 + 作者章节管理）
 */
@Tag(name = "章节")
@RestController
@RequestMapping("/chapter")
@RequiredArgsConstructor
public class ChapterController {

    private final ChapterService chapterService;

    @Operation(summary = "分页目录（不含正文，读者视角，仅可见章）")
    @GetMapping("/page/{novelId}")
    @RateLimit(name = "chapter:list", limit = 120)
    public ResponseDTO<PageResult<ChapterVO>> page(@PathVariable Long novelId,
                                                   @RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "50") long pageSize) {
        return ResponseDTO.ok(chapterService.pageVOByNovel(novelId, pageNum, pageSize));
    }

    @Operation(summary = "单章元数据（不含正文，付费章也可取）")
    @GetMapping("/{id}")
    @RateLimit(name = "chapter:list", limit = 120)
    public ResponseDTO<ChapterVO> meta(@PathVariable Long id) {
        return ResponseDTO.ok(chapterService.getChapterVO(id));
    }

    @Operation(summary = "读取章节正文（付费章需已解锁）")
    @GetMapping("/{id}/content")
    @RateLimit(name = "chapter:content", limit = 120)
    public ResponseDTO<ChapterContentVO> content(@PathVariable Long id) {
        return ResponseDTO.ok(chapterService.getContent(id));
    }

    @Operation(summary = "作者/管理员视角目录分页（全部章节含审核状态）")
    @GetMapping("/author/{novelId}")
    public ResponseDTO<PageResult<ChapterVO>> authorList(@PathVariable Long novelId,
                                                         @RequestParam(defaultValue = "1") long pageNum,
                                                         @RequestParam(defaultValue = "20") long pageSize) {
        return ResponseDTO.ok(chapterService.pageAuthorVOByNovel(novelId, pageNum, pageSize));
    }

    @Operation(summary = "新增章节（连载，章序号自增，提交审核）")
    @PostMapping
    public ResponseDTO<ChapterVO> add(@Valid @RequestBody ChapterSaveForm form) {
        return ResponseDTO.ok(chapterService.addChapter(form.getNovelId(), form));
    }

    @Operation(summary = "修改章节（影子正文，重新送审）")
    @PutMapping("/{id}")
    public ResponseDTO<ChapterVO> update(@PathVariable Long id,
                                         @Valid @RequestBody ChapterSaveForm form) {
        return ResponseDTO.ok(chapterService.updateChapter(id, form));
    }

    @Operation(summary = "删除章节（仅非已发布章）")
    @DeleteMapping("/{id}")
    public ResponseDTO<Void> delete(@PathVariable Long id) {
        chapterService.deleteChapter(id);
        return ResponseDTO.ok();
    }
}
