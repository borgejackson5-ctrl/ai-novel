package com.ainovel.module.search.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.novel.domain.form.NovelQueryForm;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.search.domain.form.SmartSearchForm;
import com.ainovel.module.search.domain.vo.SmartSearchVO;
import com.ainovel.module.search.service.AiSearchService;
import com.ainovel.module.search.service.ChapterVectorService;
import com.ainovel.module.search.service.SearchService;
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

import java.util.List;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 小说搜索接口（Elasticsearch 全文检索）
 */
@Tag(name = "搜索")
@RestController
@RequestMapping("/novel")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    private final ChapterVectorService chapterVectorService;

    private final AiSearchService aiSearchService;

    @Operation(summary = "关键词搜索小说（可叠加书库筛选：分类 / 连载状态 / 字数区间）")
    @GetMapping("/search")
    @RateLimit(name = "search", limit = 60)
    public ResponseDTO<PageResult<NovelVO>> search(
            @RequestParam String keyword,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer serialStatus,
            @RequestParam(required = false) Integer minWords,
            @RequestParam(required = false) Integer maxWords,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        NovelQueryForm filter = new NovelQueryForm();
        filter.setCategoryId(categoryId);
        filter.setSerialStatus(serialStatus);
        filter.setMinWords(minWords);
        filter.setMaxWords(maxWords);
        return ResponseDTO.ok(searchService.search(keyword, filter, page, size));
    }

    @Operation(summary = "智能搜索小说（AI 意图解析 + ES，失败自动降级关键词搜索）")
    @PostMapping("/search/smart")
    @RateLimit(name = "search:smart", limit = 10)
    public ResponseDTO<SmartSearchVO> smartSearch(@Valid @RequestBody SmartSearchForm form) {
        return ResponseDTO.ok(aiSearchService.smartSearch(form));
    }

    /**
     * 全量重建搜索索引（运维接口）。
     *
     * <p>必须显式标注 {@code @SaCheckRole}：该 controller 的路径前缀为 {@code /novel}，
     * 而该前缀下的搜索接口面向读者（仅要求登录）；全局拦截器也只执行 {@code checkLogin}。
     * 缺少角色约束时，任何登录用户均可触发一次全量重建。未加约束时，
     * 普通读者账号调用返回 {@code {"code":200,"data":60}}，将 60 本书全部重灌，
     * 而相同身份调用 {@code /admin/search/reconcile} 返回 403。
     *
     * <p>该接口不写库、不删数据，但需读出全部可见作品并重写索引，属重负载操作，
     * 多人同时触发会打满 DB 与 ES。运维入口本不应位于读者前缀下，此处就地增加约束
     * （不调整路径以避免改动部署脚本中既有的 URL）。
     */
    @Operation(summary = "全量重建搜索索引（管理员）")
    @SaCheckRole("admin")
    @PostMapping("/reindex")
    public ResponseDTO<Integer> reindex() {
        return ResponseDTO.ok(searchService.reindexAll());
    }

    /**
     * 给一本作品重建章节向量索引（运维 / 回填接口）。
     *
     * <p>限定为单本而非整个书库的原因：向量化需对每一章正文调用一次 embedding，
     * 全库几万章一次触发即为几万次外部调用（消耗平台费用且执行时间长）。
     * 全量重建需逐本执行，该方式形成天然的限流。
     *
     * <p>同样必须标注 {@code @SaCheckRole("admin")}：该前缀面向读者，缺少角色约束时，
     * 任何登录用户均可触发（与上述 reindex 接口同一问题）。
     */
    @Operation(summary = "重建某本作品的章节向量索引（管理员）")
    @SaCheckRole("admin")
    @PostMapping("/vector-reindex")
    public ResponseDTO<Integer> vectorReindex(@RequestParam Long novelId) {
        return ResponseDTO.ok(chapterVectorService.reindexNovel(novelId));
    }

    /**
     * 检索章节片段（管理员诊断接口）。
     *
     * <p>该接口的用途：「前文检索不到」是排查成本较高的故障，
     * 界面上仅表现为「本章未报告跨章问题」，与「确实没有问题」无法区分。
     * 借助该接口可直接查询「在本书中检索『那把刀有多长』返回的内容」，
     * 无需反复执行审查并查阅日志。
     *
     * <p>{@code mode} 支持 vector / keyword / hybrid：三种检索方式可在一次部署内对比，
     * 无需修改配置重启。评测脚本依赖它测试生产代码路径，而非另行实现一份检索逻辑。
     *
     * <p>该接口为只读（不写库、不改索引），但仍需 {@code @SaCheckRole("admin")}：
     * 路径前缀面向读者，而检索会原样返回章节正文；若该作品尚未发布，
     * 任何登录用户都可读取，构成内容泄漏。
     */
    @Operation(summary = "检索章节片段（管理员诊断：可对比向量 / 关键词 / 混合）")
    @SaCheckRole("admin")
    @PostMapping("/vector-search")
    public ResponseDTO<List<ChapterVectorService.ChunkHit>> vectorSearch(
            @RequestParam Long novelId,
            @RequestParam String q,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "hybrid") String mode) {
        ChapterVectorService.Mode parsed = switch (mode == null ? "" : mode.toLowerCase()) {
            case "vector" -> ChapterVectorService.Mode.VECTOR;
            case "keyword" -> ChapterVectorService.Mode.KEYWORD;
            case "auto" -> ChapterVectorService.Mode.AUTO;
            default -> ChapterVectorService.Mode.HYBRID;
        };
        return ResponseDTO.ok(chapterVectorService.search(novelId, q, topK, parsed));
    }
}
