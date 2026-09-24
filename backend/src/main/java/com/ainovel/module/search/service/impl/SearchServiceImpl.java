package com.ainovel.module.search.service.impl;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.SerialStatusEnum;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.domain.NovelVisibility;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.novel.domain.form.NovelQueryForm;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.search.domain.dto.SearchIntent;
import com.ainovel.module.search.domain.doc.NovelDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ainovel.module.search.service.SearchService;

/**
 * 小说搜索服务：走 Elasticsearch 全文检索（替代 MySQL LIKE）
 *
 * <p>技术点：
 * <ul>
 *   <li>倒排索引 + IK 分词，解决 LIKE 无法分词/不走索引的问题</li>
 *   <li>multi_match 多字段加权（title 3 倍 > intro 1.5 > tags 1）</li>
 *   <li>BM25 相关度排序 + title/intro 高亮片段</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    /** 搜索单页最大条数：ES 深分页开销高于 DB 列表查询，上限收紧到 50 以防止超大 size 影响集群 */
    private static final int MAX_SIZE = 50;

    /**
     * ES 的 from+size 结果窗口上限，对应索引设置 index.max_result_window（默认 1 万）。
     * 超出该值会抛出「Result window is too large」并返回 500，因此页码必须一并钳位。
     */
    private static final int MAX_RESULT_WINDOW = 10_000;

    /**
     * 全量重建索引时每批取多少条（DB 侧游标用）。
     *
     * <p>不得超过 MybatisPlusConfig.MAX_LIMIT（200）：分页插件会将超限的 size 静默改小，
     * 而本方法的终止判据使用本页实际大小（{@code page.getSize()}），两者必须自洽。
     * 若此处取 500，插件将本页改为 200 → 200 &lt; 200 为假不会 break，看似正常；
     * 但若终止判据改用常量 500，则第一页即 break，索引只写入前 200 本且不报错。
     */
    private static final int REINDEX_PAGE_SIZE = 200;

    /**
     * 直接使用 ElasticsearchOperations 读写文档，不使用 Spring Data Repository。
     *
     * <p>原因在启动期：`SimpleElasticsearchRepository` 的构造函数会调用
     * `createIndexAndMappingIfNeeded()` 执行 exists() / createWithMapping()，
     * 该过程会实际连接 ES。ES 未启动（或网络不通）时构造失败，导致整个应用无法启动，
     * 下方的「ES 不可用则降级到 MySQL」逻辑无法执行。
     * 改用 ElasticsearchOperations 后，启动期不再访问 ES，连接问题仅在真正读写时暴露。
     */
    private final ElasticsearchOperations elasticsearchOperations;

    private final NovelService novelService;

    private final CategoryService categoryService;

    /**
     * 关键词搜索（不带书库筛选）
     *
     * @param keyword 关键词
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     */
    public PageResult<NovelVO> search(String keyword, int page, int size) {
        return search(keyword, null, page, size);
    }

    /**
     * 关键词搜索（可叠加书库筛选条件）
     *
     * <p>筛选条件（分类 / 连载状态 / 字数区间）由用户主动选择，因此走 filter 硬过滤。
     * 需与智能搜索中模型推断出的分类区分：后者只能作为 should 加分，
     * 因为模型判断错误会直接过滤掉正确结果。
     *
     * @param filter 书库筛选条件，null 表示不筛
     */
    public PageResult<NovelVO> search(String keyword, NovelQueryForm filter, int page, int size) {
        if (!StringUtils.hasText(keyword)) {
            return PageResult.of(0, page, size, List.of());
        }
        size = clampSize(size);
        page = clampPage(page, size);

        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    // multi_match 多字段加权 + 过滤上架状态
                    b.must(m -> m.multiMatch(mm -> mm
                            .query(keyword.trim())
                            .fields("title^3.0", "intro^1.5", "tags^1.0")));
                    b.filter(f -> f.term(t -> t.field("status").value(1)));
                    if (filter != null) {
                        if (filter.getCategoryId() != null) {
                            b.filter(f -> f.term(t -> t.field("categoryId").value(filter.getCategoryId())));
                        }
                        if (filter.getSerialStatus() != null) {
                            b.filter(f -> f.term(t -> t.field("serialStatus").value(filter.getSerialStatus())));
                        }
                        if (filter.getMinWords() != null) {
                            b.filter(f -> f.range(r -> r.number(n -> n
                                    .field("wordCount").gte(filter.getMinWords().doubleValue()))));
                        }
                        if (filter.getMaxWords() != null) {
                            b.filter(f -> f.range(r -> r.number(n -> n
                                    .field("wordCount").lte(filter.getMaxWords().doubleValue()))));
                        }
                    }
                    return b;
                }))
                .withPageable(PageRequest.of(page - 1, size))
                .build();

        try {
            return doSearch(query, page, size);
        } catch (Exception e) {
            // ES 不可用不得使搜索直接返回 500：搜索是读者的主链路，退化为数据库模糊查询
            // 仍可返回结果（代价是响应慢，且没有分词与相关度排序）。
            log.error("Elasticsearch 检索失败，降级为数据库模糊查询: keyword={}", keyword, e);
            return searchByDatabase(keyword, filter, page, size);
        }
    }

    /**
     * 降级兜底：MySQL 子串匹配。
     *
     * <p>仅在 ES 不可用时进入该路径，因此实现保持简单。该路径保证的是可用性而非体验：
     * LIKE 无法分词、无法按相关度排序，数据量增长后全表扫描会成为新的瓶颈，
     * 因此这是应急通道，不是常规方案。日志按 ERROR 记录，出现即表示需要修复 ES。
     *
     * <p>筛选条件与正常路径保持一致（同样仅使用用户主动选择的条件），
     * 保证降级前后可检索的结果集合不因路径不同而变化。
     */
    private PageResult<NovelVO> searchByDatabase(String keyword, NovelQueryForm filter, int page, int size) {
        // 查询委托给 novel 模块：可见性口径与匹配字段由其实现，降级路径不会与主路径产生分歧
        PageResult<Novel> result = novelService.searchByKeyword(keyword, filter, page, size);
        Map<Long, String> categoryNames = categoryService.getNameMap();
        List<NovelVO> list = result.getList().stream().map(d -> {
            NovelVO vo = new NovelVO();
            vo.setId(d.getId());
            vo.setTitle(d.getTitle());
            vo.setIntro(d.getIntro());
            vo.setTags(d.getTags());
            vo.setCategoryId(d.getCategoryId());
            vo.setCategoryName(categoryNames.get(d.getCategoryId()));
            vo.setReadCount(d.getReadCount());
            vo.setLikeCount(d.getLikeCount());
            vo.setTotalChapters(d.getTotalChapters());
            vo.setStatus(d.getStatus());
            vo.setSerialStatus(d.getSerialStatus());
            vo.setSerialStatusText(SerialStatusEnum.textOf(d.getSerialStatus()));
            return vo;
        }).toList();
        return PageResult.of(result.getTotal(), result.getCurrent(), result.getSize(), list);
    }

    /**
     * 智能搜索：按 AI 解析出的结构化意图查询
     *
     * <p>keywords 与 tags 组成必中组（组内 should，命中任一条即可），参与 BM25 打分，
     * 标签命中提权（tags^2.0）以体现 AI 明确识别的题材。
     *
     * <p>推断出的分类必须与用户主动选择的分类区别对待：前者由模型推断，非用户指定，
     * 因此既不能作为 filter，也不能作为普通 should：
     * <ul>
     *   <li>作为 filter：模型分类判断错误会导致零召回。以「西游记」为查询词时，filter 版本返回 0 条。</li>
     *   <li>作为普通 should：分类命中会单独使整个分类通过召回。以「西游记」为查询词时返回 9 条
     *       （《西游记》+ 其余 8 本古典名著），搜索精度被稀释。</li>
     * </ul>
     * 当前实现为「分类仅作兜底」：主查询中分类仅占 0.5 权重（影响排序，不决定召回）；
     * 仅当关键词无任何命中时，才降级使用分类召回一批结果。
     *
     * @param intent AI 解析出的搜索意图（keywords/tags 至少一个非空）
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     */
    public PageResult<NovelVO> smartSearch(SearchIntent intent, int page, int size) {
        Long categoryId = intent.getCategoryId();
        size = clampSize(size);
        page = clampPage(page, size);

        NativeQuery primary = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    b.must(m -> m.bool(kb -> {
                        for (String keyword : intent.getKeywords()) {
                            kb.should(s -> s.multiMatch(mm -> mm
                                    .query(keyword)
                                    .fields("title^3.0", "intro^1.5", "tags^1.0")));
                        }
                        for (String tag : intent.getTags()) {
                            kb.should(s -> s.multiMatch(mm -> mm
                                    .query(tag)
                                    .fields("tags^2.0", "title^1.5")));
                        }
                        kb.minimumShouldMatch("1");
                        return kb;
                    }));
                    if (categoryId != null) {
                        // boost < 1：只在同分区间表达倾向，不压过真正命中的关键词
                        b.should(s -> s.term(t -> t.field("categoryId").value(categoryId).boost(0.5f)));
                    }
                    b.filter(f -> f.term(t -> t.field("status").value(1)));
                    return b;
                }))
                .withPageable(PageRequest.of(page - 1, size))
                .build();
        PageResult<NovelVO> result = doSearch(primary, page, size);
        if (result.getTotal() > 0 || categoryId == null) {
            return result;
        }

        // 关键词无任何命中时（如查询「想看修仙的小说」而库中没有任何「修仙」字样），
        // 才使用推断出的分类兜底；分类仅作兜底，不用于放宽主查询
        log.debug("智能搜索关键词无命中，回退分类 {} 兜底", categoryId);
        NativeQuery fallback = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("status").value(1)))
                        .filter(f -> f.term(t -> t.field("categoryId").value(categoryId)))))
                .withPageable(PageRequest.of(page - 1, size))
                .build();
        return doSearch(fallback, page, size);
    }

    /**
     * 分页 size 钳位：下界 1、上界 {@value #MAX_SIZE}，防非法/超大 size 直达 ES
     */
    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }

    /**
     * 分页页码钳位：保证 from = (page-1)*size 不越过 ES 的结果窗口上限。
     *
     * <p>越界时钳制到最后一页而不抛错：列表场景下多翻几页不应返回 500。
     * 如需支持深翻页，正确做法是 search_after / PIT（游标翻页）；本项目列表数据量为几十条，
     * 无需该能力，钳位即可。
     */
    private int clampPage(int page, int size) {
        int maxPage = Math.max(1, MAX_RESULT_WINDOW / size);
        return Math.min(Math.max(page, 1), maxPage);
    }

    /**
     * 执行查询并转换结果（关键词/智能搜索共用）
     */
    private PageResult<NovelVO> doSearch(NativeQuery query, int page, int size) {
        query.setHighlightQuery(new HighlightQuery(
                new Highlight(List.of(new HighlightField("title"), new HighlightField("intro"))),
                NovelDoc.class));

        SearchHits<NovelDoc> hits = elasticsearchOperations.search(query, NovelDoc.class);

        Map<Long, String> categoryNames = categoryService.getNameMap();
        List<NovelVO> list = new ArrayList<>();
        for (SearchHit<NovelDoc> hit : hits) {
            NovelDoc doc = hit.getContent();
            NovelVO vo = new NovelVO();
            vo.setId(doc.getId());
            vo.setTitle(doc.getTitle());
            // 高亮片段单独放 highlightTitle，前端 v-html 渲染
            List<String> titleHl = hit.getHighlightField("title");
            if (titleHl != null && !titleHl.isEmpty()) {
                vo.setHighlightTitle(titleHl.get(0));
            }
            vo.setIntro(doc.getIntro());
            vo.setTags(doc.getTags());
            vo.setCategoryId(doc.getCategoryId());
            vo.setCategoryName(categoryNames.get(doc.getCategoryId()));
            vo.setReadCount(doc.getReadCount());
            vo.setLikeCount(doc.getLikeCount());
            vo.setTotalChapters(doc.getTotalChapters());
            vo.setStatus(doc.getStatus());
            vo.setSerialStatus(doc.getSerialStatus());
            vo.setSerialStatusText(SerialStatusEnum.textOf(doc.getSerialStatus()));
            list.add(vo);
        }

        return PageResult.of(hits.getTotalHits(), page, size, list);
    }

    /**
     * 确保搜索索引存在，且 mapping 按实体注解（@Document/@Field）建立
     *
     * <p>必须显式调用，不能依赖 save/saveAll 自动建索引。
     * Spring Data 在索引缺失时不会按实体映射建索引，而是交由 ES 动态映射处理，
     * 导致 title/intro/tags 上配置的 ik_max_word / ik_smart 全部丢失，
     * 中文索引退化为单字切分。且查询侧仍按词切分，两侧不一致，
     * 表现为搜索无结果或返回大量无关结果（以「三国演义」查询时显式使用 IK 命中 0 条）。
     */
    public void ensureIndex() {
        IndexOperations ops = elasticsearchOperations.indexOps(NovelDoc.class);
        if (!ops.exists()) {
            ops.createWithMapping();
            log.info("搜索索引不存在，已按实体映射创建（含 IK 分析器）");
            return;
        }
        warnIfAnalyzerMissing(ops);
    }

    /**
     * 索引已存在时无法再改字段分析器（ES 不允许修改已存在字段的 mapping），
     * 只能在启动时告警，提示删索引后重建。
     */
    private void warnIfAnalyzerMissing(IndexOperations ops) {
        try {
            Object props = ops.getMapping().get("properties");
            if (props instanceof Map<?, ?> p && p.get("title") instanceof Map<?, ?> title
                    && !title.containsKey("analyzer")) {
                log.warn("搜索索引 title 字段没有 analyzer —— IK 分词未生效，"
                        + "需删除索引后重新执行全量重建才会恢复");
            }
        } catch (Exception e) {
            log.debug("读取索引 mapping 失败，跳过分析器校验: {}", e.getMessage());
        }
    }

    /**
     * 全量重建索引：把 MySQL 中「对外可分发」的小说写入 ES
     *
     * <p>用于：首次启动灌入 seed 数据、ES 索引丢失后的手动重建
     *
     * @return 同步条数
     */
    public int reindexAll() {
        // 先确保索引按实体映射建好，否则后续 saveAll 会由 ES 动态映射创建一个
        // 没有 IK 分析器的索引（全量重建正是该问题的修复入口，此处必须补齐）
        ensureIndex();
        // 分页游标 + saveAll 批量写入，避免全量加载内存及逐条 save 的 N+1 网络往返。
        // 仅重建可见作品（已上架 + 审核通过或变更待审）：将未过审 / 已下架作品
        // 写入索引没有意义，只会在管理员直接改库等旁路场景下产生
        // 「可搜索、访问返回 404」的脏文档。口径与书库分页、搜索同步消费者一致。
        int total = 0;
        int pageNo = 1;
        while (true) {
            List<Novel> records = novelService.pageVisibleForIndex(pageNo, REINDEX_PAGE_SIZE);
            if (records.isEmpty()) {
                break;
            }
            elasticsearchOperations.save(records.stream().map(this::toDoc).toList());
            total += records.size();
            // 终止判据可直接使用常量比较：size 由 novel 侧用 LIMIT 计算，不经过分页插件，
            // 不会被静默改小（历史缺陷即由该项导致）
            if (records.size() < REINDEX_PAGE_SIZE) {
                break;
            }
            pageNo++;
        }
        log.info("全量重建搜索索引完成: {} 条小说", total);
        return total;
    }

    /**
     * 按 id 同步一条索引：DB 侧可分发则写入（覆盖），不可见（已删除 / 下架 / 未过审）则从索引移除。
     *
     * <p>MQ 同步消费者与定时对账任务共用该逻辑。两条路径若各自实现一份可见性判断，
     * 最终会产生分歧，这是「可搜索、访问返回 404」最隐蔽的来源。
     *
     * <p>不可见也必须删除：作品下架或审核被驳回后若不从索引移除，
     * 索引中将长期保留可搜索、访问返回 404 的文档。查询时的 status 过滤仅为被动兜底，
     * 索引本身只应存放对外可分发的作品。
     *
     * @return true = 已写入索引；false = 索引中不存在该文档（不可见或已删）
     */
    public boolean syncOne(Long novelId) {
        Novel novel = novelService.getNovel(novelId);
        if (novel == null || !isIndexable(novel)) {
            elasticsearchOperations.delete(String.valueOf(novelId), NovelDoc.class);
            return false;
        }
        elasticsearchOperations.save(toDoc(novel));
        return true;
    }

    /**
     * 索引中现有文档条数。首次启动据此判断是否需要全量灌入，避免重复写入。
     *
     * <p>仅取总数、不拉取文档，该判断只需要一个数字。
     */
    public long indexedCount() {
        return elasticsearchOperations.count(
                NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build(), NovelDoc.class);
    }

    /**
     * 判断是否应当出现在索引中。
     *
     * <p>口径来自 {@link NovelVisibility}，与书库分页、全量重建、定时对账共用同一份，不各自实现。
     */
    public static boolean isIndexable(Novel novel) {
        // 口径委托给 NovelVisibility：书库分页、全量重建、索引同步、定时对账共用同一份判定
        return NovelVisibility.isVisible(novel);
    }

    /**
     * 拉取索引中的全部文档 id（对账用）。
     *
     * <p>仅取 id 不取 _source：索引中最占空间的是简介和标签，对账只需知道存在哪些 id。
     *
     * <p>使用 searchForStream 而非 from+size 翻页：后者受
     * {@code index.max_result_window}（默认 1 万）限制，索引超过 1 万条时，
     * 翻至第 10 页即抛出「Result window is too large」，导致对账失败。
     * 而对账必须取全量、不能钳位（普通检索钳掉几页只是少看几页；对账钳掉的部分
     * 恰为漏掉的那批，会将漂移误判为一致）。流式查询内部使用 scroll/PIT，
     * 取任意条数都不会触及窗口上限。
     */
    public Set<Long> loadIndexedIds() {
        Set<Long> ids = new HashSet<>();
        NativeQuery query = NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build();
        try (SearchHitsIterator<NovelDoc> it =
                     elasticsearchOperations.searchForStream(query, NovelDoc.class)) {
            while (it.hasNext()) {
                try {
                    ids.add(Long.valueOf(it.next().getId()));
                } catch (NumberFormatException ignored) {
                    // 索引中出现非数字 id 说明存在手工写入，跳过即可，不应导致对账整体失败
                }
            }
        }
        return ids;
    }

    private NovelDoc toDoc(Novel novel) {
        NovelDoc doc = new NovelDoc();
        doc.setId(novel.getId());
        doc.setTitle(novel.getTitle());
        doc.setIntro(novel.getIntro());
        doc.setTags(novel.getTags());
        doc.setCategoryId(novel.getCategoryId());
        doc.setReadCount(novel.getReadCount());
        doc.setLikeCount(novel.getLikeCount());
        doc.setTotalChapters(novel.getTotalChapters());
        doc.setWordCount(novel.getWordCount());
        doc.setStatus(novel.getStatus());
        doc.setSerialStatus(novel.getSerialStatus());
        return doc;
    }
}
