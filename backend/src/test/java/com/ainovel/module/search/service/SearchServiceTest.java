package com.ainovel.module.search.service;

import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.search.domain.doc.NovelDoc;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.search.service.impl.SearchServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.AggregationsContainer;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.TotalHitsRelation;
import org.springframework.data.elasticsearch.core.query.Query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SearchService#reindexAll()} 的分页遍历测试：约束「全量重建不能提前结束」。
 *
 * <p>背景：此处曾使用 {@code int size = 500} + {@code if (records.size() < size) break}。
 * 分页插件会把超限的页大小静默改小到 {@code MybatisPlusConfig.MAX_LIMIT(200)}，
 * 于是每页实际 200 条、却与常量 500 比较，{@code 200 < 500} 成立，第一页即 break，
 * 索引只写入前 200 本，且不产生任何报错。当时库中只有 60 本，无法暴露该问题。
 *
 * <p>该测试用 {@code thenAnswer} 模拟插件把 size 改小的行为（而不是直接返回一个固定大小的
 * Page），因此旧写法会失败、新写法才通过；否则测试在两种实现下都会通过，等于没有约束。
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    /** 与 MybatisPlusConfig.MAX_LIMIT 对齐：模拟分页插件把超限的 size 改小到这个值 */
    private static final int GLOBAL_MAX_LIMIT = 200;

    /** 构造 250 本可见作品：第一页 200、第二页 50，只有翻到第二页才能取全 */
    private static final int TOTAL_NOVELS = 250;

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private NovelService novelService;

    @Mock
    private CategoryService categoryService;

    @Mock
    private IndexOperations indexOperations;

    private SearchService searchService;

    @BeforeEach
    void stubIndex() {
        searchService = new SearchServiceImpl(elasticsearchOperations, novelService, categoryService);
        // ensureIndex() 会先确认索引存在；已存在且 mapping 中包含 analyzer 时走最短分支。
        // 使用 lenient：只有 reindexAll 那条路径会触发索引操作，loadIndexedIds 不会，
        // 严格模式下「未使用的 stub」会被判失败，而这里本来就是按需准备。
        lenient().when(elasticsearchOperations.indexOps(NovelDoc.class)).thenReturn(indexOperations);
        lenient().when(indexOperations.exists()).thenReturn(true);
        lenient().when(indexOperations.getMapping()).thenReturn(
                Map.of("properties", Map.of("title", Map.of("type", "text", "analyzer", "ik_max_word"))));
    }

    @Test
    @DisplayName("全量重建 → 遍历取完全部作品；每批页大小固定，不随数据量放大")
    void reindexAll_scansAllPagesWithConstantBatchSize() {
        List<Integer> batchSizes = new ArrayList<>();
        when(novelService.pageVisibleForIndex(anyInt(), anyInt())).thenAnswer(invocation -> {
            int pageNo = invocation.getArgument(0);
            int pageSize = invocation.getArgument(1);
            batchSizes.add(pageSize);
            int offset = (pageNo - 1) * pageSize;
            int remaining = (int) Math.max(0, TOTAL_NOVELS - offset);
            return buildNovels(offset, Math.min(remaining, pageSize));
        });

        int total = searchService.reindexAll();

        assertEquals(TOTAL_NOVELS, total,
                "只索引了 " + total + " 本 —— 说明遍历提前 break 了");
        // 250 本 = 200 + 50，第二页不满即结束 → 正好两批
        assertEquals(2, batchSizes.size(), "翻页次数不对：" + batchSizes);
        assertEquals(1, batchSizes.stream().distinct().count(), "每批页大小应固定：" + batchSizes);
        assertTrue(batchSizes.get(0) <= GLOBAL_MAX_LIMIT,
                "单批页大小不该超过全局上限：" + batchSizes.get(0));
    }

    @Test
    @DisplayName("全量重建 → 每批页大小恒定，不随数据量放大")
    void reindexAll_usesConstantBatchSize() {
        List<Integer> requestedSizes = new ArrayList<>();
        when(novelService.pageVisibleForIndex(anyInt(), anyInt())).thenAnswer(invocation -> {
            int pageNo = invocation.getArgument(0);
            int pageSize = invocation.getArgument(1);
            requestedSizes.add(pageSize);
            // 第一次返回满页，第二次返回空页 → 循环结束
            return pageNo == 1 ? buildNovels(0, pageSize) : List.of();
        });

        searchService.reindexAll();

        assertEquals(1, requestedSizes.stream().distinct().count(),
                "每批页大小应恒定，实际：" + requestedSizes);
        assertTrue(requestedSizes.get(0) <= GLOBAL_MAX_LIMIT,
                "单批页大小不该超过全局上限：" + requestedSizes.get(0));
    }

    private static List<Novel> buildNovels(long startId, int count) {
        List<Novel> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Novel novel = new Novel();
            novel.setId(startId + i + 1);
            novel.setTitle("作品" + (startId + i + 1));
            list.add(novel);
        }
        return list;
    }

    /**
     * 对账拉取全量 id 的路径：必须走流式查询，不能走 from+size 翻页。
     *
     * <p>翻页实现受 {@code index.max_result_window}（默认 1 万）限制：索引一旦超过 1 万条，
     * 翻到第 10 页就会抛「Result window is too large」，对账整体失败。
     * 普通检索可以钳位（少看几页影响有限），对账不能：被钳掉的那部分恰好是漏掉的那批，
     * 结果会把漂移判成「一致」，其影响大于直接失败。
     *
     * <p>断言「没有调用分页 search」而不是只比较结果条数：两种实现在小数据量下结果相同，
     * 只比较结果时该测试等于没写（旧实现也能通过）。
     */
    @Test
    @DisplayName("对账拉 id 走流式查询且会关闭：不能走 from+size 翻页（索引过万会撞结果窗口）")
    void loadIndexedIds_usesStreamQueryAndCloses() {
        AtomicBoolean closed = new AtomicBoolean(false);
        when(elasticsearchOperations.searchForStream(any(Query.class), eq(NovelDoc.class)))
                .thenReturn(iteratorOf(List.of("1", "2"), closed));

        // 先证明测试脚手架本身可用：匿名迭代器可迭代、构造的 SearchHit 能取到 id。
        // 缺少这一步时，后续断言失败无法区分是被测代码错误还是测试脚手架错误
        SearchHitsIterator<NovelDoc> probe = iteratorOf(List.of("9"), new AtomicBoolean());
        assertTrue(probe.hasNext(), "匿名迭代器自身有问题");
        assertEquals("9", probe.next().getId(), "SearchHit 构造器取不到 id");

        Set<Long> ids = searchService.loadIndexedIds();

        assertEquals(Set.of(1L, 2L), ids);
        // 核心判据：不得走分页查询
        verify(elasticsearchOperations, never()).search(any(Query.class), eq(NovelDoc.class));
        // 流式查询必须关闭：不关就是 ES 侧 scroll / PIT 上下文泄漏
        assertTrue(closed.get(), "流式查询没有关闭 —— scroll 上下文会泄漏");
    }

    @Test
    @DisplayName("对账拉 id：索引里有非数字 id（有人手工写过）跳过即可，不让对账整体失败")
    void loadIndexedIds_skipsNonNumericId() {
        when(elasticsearchOperations.searchForStream(any(Query.class), eq(NovelDoc.class)))
                .thenReturn(iteratorOf(List.of("7", "manual-doc"), new AtomicBoolean()));

        assertEquals(Set.of(7L), searchService.loadIndexedIds());
    }

    private static SearchHit<NovelDoc> docHit(String id) {
        // 使用真实构造器而不是 mock：SearchHit 的 getId() 在 mock 下取不到值。
        // 参数顺序是 (index, id, routing, score, ...)，而不是源码中看起来的 (id, index, ...)：
        // 已核对 spring-data-elasticsearch 5.5.2 的字节码，构造器把第 1 个参数赋给 index、
        // 第 2 个赋给 id、第 3 个赋给 routing。写反不会报错，只是 getId() 返回 index，
        // 表现为「取回的 id 全是索引名」。
        return new SearchHit<>("novel", id, null, 1.0f, new Object[0],
                Map.of(), Map.of(), null, null, List.of(), null);
    }

    /**
     * 手写一个迭代器实现而不 mock：Iterator 的几个判定方法在 mock 下行为不易把握，
     * 而这层要测试的本来就是「拿到迭代器之后如何使用」，用真实现更贴近。
     * {@code closed} 用于观察 close 是否被调用（try-with-resources 是否生效）。
     */
    private static SearchHitsIterator<NovelDoc> iteratorOf(List<String> ids, AtomicBoolean closed) {
        return new SearchHitsIterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < ids.size();
            }

            @Override
            public SearchHit<NovelDoc> next() {
                return docHit(ids.get(index++));
            }

            @Override
            public void close() {
                closed.set(true);
            }

            @Override
            public AggregationsContainer<?> getAggregations() {
                return null;
            }

            @Override
            public float getMaxScore() {
                return 0;
            }

            @Override
            public Duration getExecutionDuration() {
                return Duration.ZERO;
            }

            @Override
            public long getTotalHits() {
                return ids.size();
            }

            @Override
            public TotalHitsRelation getTotalHitsRelation() {
                return TotalHitsRelation.EQUAL_TO;
            }
        };
    }
}
