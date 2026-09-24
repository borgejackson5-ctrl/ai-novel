package com.ainovel.module.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.ainovel.common.client.DashScopeProperties;
import com.ainovel.common.client.EmbeddingClient;
import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.module.ai.spi.ChapterRetrievalPort;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.search.domain.doc.ChapterChunkDoc;
import com.ainovel.module.search.service.ChapterVectorService;
import com.ainovel.module.search.support.ChapterChunker;
import com.ainovel.module.search.support.HybridRanker;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 章节正文的向量索引，检索部分采用混合模式（向量 + 关键词）。
 *
 * <p>实现取舍如下，详见各方法说明：
 * <ul>
 *   <li>写入顺序为「先写新块、再删旧块」；若反序执行且中途失败，该章将从索引中消失，
 *       而检索不到不会报错（见 {@link #indexChapter}）；</li>
 *   <li>检索失败返回空列表而非抛出异常：审查链路上检索不到必须能退回
 *       「当前章 ±10 章」策略，不能因 ES 异常导致整章审查失败（见 {@link #search}）；</li>
 *   <li>两路各自容错：embedding（外部服务）不可用时关键词一路仍可用，反之亦然；</li>
 *   <li>建索引时校验向量维度与 text 字段的可检索性，不一致直接失败（见 {@link #ensureIndex}）。</li>
 * </ul>
 *
 * <p>采用混合检索的原因：两路查询的覆盖盲区不同。向量按语义匹配（「伞的骨架是几根」
 * 这类不含原词的说法也能命中），关键词按字面匹配（专名、术语命中率高）。
 * 在真实长篇数据上合并两路可将章覆盖率从 16~23/26 提升到 21~26/26。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterVectorServiceImpl implements ChapterVectorService, ChapterRetrievalPort {

    /** 向量字段名，拼写错误仅在查询时暴露 */
    private static final String FIELD_VECTOR = "vector";

    /** 正文块字段名（混合检索的关键词一路查该字段，必须可检索，见 ensureIndex） */
    private static final String FIELD_TEXT = "text";

    /**
     * 单次检索返回的最大条数。给模型看的上下文并非越多越好：分块增加既稀释注意力也提高费用，
     * 且实际所需的前文通常位于前 3~5 条中。
     */
    private static final int MAX_TOP_K = 20;

    /**
     * kNN 候选集大小倍数。ES 的 knn 先粗筛 numCandidates 个候选、再精排取 k 个；
     * 候选过少会使本应命中的片段在粗筛阶段被丢弃，导致召回率下降且不报错。
     */
    private static final int CANDIDATE_FACTOR = 10;

    /** RRF 融合常数。取 10 而非文献默认的 60，理由见 HybridRanker */
    private static final int RRF_K = 10;

    /** 混合检索时每一路的候选倍数（融合为重排过程，候选过少会丢弃另一路可召回的结果） */
    private static final int CANDIDATE_MULTIPLIER = 2;

    private static final int MIN_CANDIDATES = 10;

    /**
     * 是否启用关键词一路（混合检索）。默认启用。
     *
     * <p>依据：两路覆盖盲区不同。在真实长篇数据上，向量 Top3 覆盖 23/26、关键词 Top3 覆盖 15/26，
     * 合并后为 24/26；另一数据集为 16~17/26 → 21~22/26。
     *
     * <p>保留该开关用于故障时回退（置 false 即退回纯向量检索），无需改代码重新发布。
     */
    @Value("${app.rag.hybrid-enabled:true}")
    private boolean hybridEnabled;

    private final ElasticsearchClient elasticsearchClient;

    private final ElasticsearchOperations elasticsearchOperations;

    private final ChapterChunker chapterChunker;

    private final EmbeddingClient embeddingClient;

    private final ChapterService chapterService;

    private final DashScopeProperties dashScopeProperties;

    @Override
    public void ensureIndex() {
        IndexOperations ops = elasticsearchOperations.indexOps(ChapterChunkDoc.class);
        if (!ops.exists()) {
            ops.createWithMapping();
            log.info("章节向量索引不存在，已按实体映射创建（dims={}）",
                    dashScopeProperties.getEmbeddingDimensions());
            return;
        }
        Map<String, Object> properties = mappingProperties(ops);
        Integer actual = dimensionsOf(properties);
        int expected = dashScopeProperties.getEmbeddingDimensions();
        if (actual != null && actual != expected) {
            // 维度不一致意味着每次写入都会失败，且异常发生在写入阶段，
            // 与配置变更位置相距较远，因此在此处显式抛出并说明原因
            throw new IllegalStateException("章节向量索引的维度是 " + actual
                    + "，而当前配置 dashscope.embedding-dimensions=" + expected
                    + "。ES 改不了已存在字段的维度：要么把配置改回去，要么删掉 "
                    + INDEX_NAME + " 索引重建。");
        }
        // text 必须可检索：混合检索的关键词一路依赖其倒排索引。
        // 该问题与向量维度不一致同属「不报错但功能失效」：match 查询在 index=false 的
        // 字段上返回 0 条且不抛异常，混合检索会静默退化为纯向量（指标下降也难以定位）
        if (hybridEnabled && !textSearchable(properties)) {
            throw new IllegalStateException("章节向量索引的 text 字段不可检索（或分词器不是 IK）："
                    + "混合检索的关键词那一半会永远查不到东西，而且不报错。"
                    + "ES 改不了已存在字段的 analyzer 与 index：请删掉 " + INDEX_NAME
                    + " 索引重建，再重新回填（POST /novel/vector-reindex?novelId=）。");
        }
        // 输出实际读到的维度与 analyzer：校验仅报告不一致，运维通常还需了解当前实际配置
        log.info("章节向量索引已就绪：dims={}　text.analyzer={}　text.index={}　hybrid={}",
                actual, analyzerOf(properties), indexOf(properties), hybridEnabled);
    }

    /** mapping 中 text 字段的 analyzer（ES 未返回该项时为 null） */
    private String analyzerOf(Map<String, Object> properties) {
        if (properties.get(FIELD_TEXT) instanceof Map<?, ?> text && text.get("analyzer") != null) {
            return String.valueOf(text.get("analyzer"));
        }
        return "（ES 未返回 analyzer）";
    }

    private String indexOf(Map<String, Object> properties) {
        if (properties.get(FIELD_TEXT) instanceof Map<?, ?> text) {
            return text.get("index") == null ? "true（默认，ES 省略不写）" : String.valueOf(text.get("index"));
        }
        return "（没有 text 字段）";
    }

    @Override
    public int indexChapter(Long novelId, Long chapterId) {
        if (novelId == null || chapterId == null) {
            return 0;
        }
        Chapter chapter = chapterService.getById(chapterId);
        if (chapter == null) {
            // 章节已删除：清除残留分块，否则检索会返回已不存在的正文
            deleteChunks(chapterId, 0);
            return 0;
        }
        List<ChapterChunker.Chunk> chunks = chapterChunker.split(chapter.currentBody());
        if (chunks.isEmpty()) {
            deleteChunks(chapterId, 0);
            return 0;
        }
        long start = System.currentTimeMillis();
        List<float[]> vectors = embeddingClient.embed(
                chunks.stream().map(ChapterChunker.Chunk::text).toList());

        List<ChapterChunkDoc> docs = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChapterChunker.Chunk chunk = chunks.get(i);
            ChapterChunkDoc doc = new ChapterChunkDoc();
            doc.setId(chunkId(novelId, chapterId, chunk.seq()));
            doc.setNovelId(novelId);
            doc.setChapterId(chapterId);
            doc.setChapterNo(chapter.getChapterNo());
            doc.setSeq(chunk.seq());
            doc.setText(chunk.text());
            doc.setVector(vectors.get(i));
            docs.add(doc);
        }
        // 先写后删：同 seq 的分块被覆盖，多余的历史分块随后清除。
        // 若反序执行（先删后写）且中途失败，该章将从索引中完全消失，
        // 而检索不到不会报错，只会使跨章核对缺少依据
        elasticsearchOperations.save(docs);
        deleteChunks(chapterId, docs.size());

        log.info("章节向量索引：novelId={} chapterId={} 第{}章 切成 {} 块，耗时 {}ms",
                novelId, chapterId, chapter.getChapterNo(), docs.size(),
                System.currentTimeMillis() - start);
        return docs.size();
    }

    @Override
    public List<ChunkHit> search(Long novelId, String query, int topK) {
        return search(novelId, query, topK, Mode.AUTO);
    }

    @Override
    public List<ChunkHit> search(Long novelId, String query, int topK, Mode mode) {
        if (novelId == null || !StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        Mode effective = resolveMode(mode);
        int k = Math.min(topK, MAX_TOP_K);
        long start = System.currentTimeMillis();
        List<ChunkHit> hits;
        try {
            hits = switch (effective) {
                case VECTOR -> vectorHits(novelId, query, k);
                case KEYWORD -> keywordHits(novelId, query, k);
                case HYBRID -> HybridRanker.fuse(
                        List.of(vectorHits(novelId, query, candidateSize(k)),
                                keywordHits(novelId, query, candidateSize(k))),
                        k, RRF_K);
                default -> List.of();   // AUTO 已在 resolveMode 中解析，不会进入该分支
            };
        } catch (Exception e) {
            // 两路各自的异常已在内部捕获，此处为最后一道防线：检索失败不得
            // 导致调用方（审查流程）异常，其必须能退回「当前章 ±10 章」策略
            log.warn("章节检索失败，本次按「没有检索到」处理（调用方应退回邻近检索）：novelId={} mode={}",
                    novelId, effective, e);
            return List.of();
        }
        log.info("章节检索：novelId={} mode={} topK={} 命中 {} 条，耗时 {}ms，query={}",
                novelId, effective, k, hits.size(), System.currentTimeMillis() - start, abbreviate(query));
        return hits;
    }

    /** AUTO 按配置解析；其余原样返回 */
    private Mode resolveMode(Mode mode) {
        Mode requested = mode == null ? Mode.AUTO : mode;
        return requested == Mode.AUTO ? (hybridEnabled ? Mode.HYBRID : Mode.VECTOR) : requested;
    }

    /**
     * 向量检索路径：按语义匹配，无需精确措辞即可命中。
     *
     * <p>失败时返回空列表而不抛出异常：embedding 为外部服务，其抖动不应导致整次检索失败，
     * 关键词一路不依赖任何外部服务，仍可保证结果可用。这是混合检索的附加收益。
     */
    private List<ChunkHit> vectorHits(Long novelId, String query, int size) {
        try {
            float[] queryVector = embeddingClient.embed(List.of(query)).get(0);
            List<Float> vector = new ArrayList<>(queryVector.length);
            for (float value : queryVector) {
                vector.add(value);
            }
            SearchResponse<ObjectNode> response = elasticsearchClient.search(request -> request
                    .index(INDEX_NAME)
                    .knn(knn -> knn
                            .field(FIELD_VECTOR)
                            .queryVector(vector)
                            .k(size)
                            .numCandidates(Math.max(size * CANDIDATE_FACTOR, 50))
                            // 限定为本书内检索：跨书检索会将其他作品的设定误作本书前文
                            .filter(filter -> filter.term(term -> term
                                    .field("novelId").value(novelId))))
                    .source(source -> source.filter(field -> field
                            .includes("chapterId", "chapterNo", "seq", "text"))),
                    ObjectNode.class);
            return toHits(response);
        } catch (Exception e) {
            log.warn("章节检索：向量那一路失败，本次只用关键词那一路（若有）：novelId={}", novelId, e);
            return List.of();
        }
    }

    /**
     * 关键词检索路径：IK 倒排索引，专名与术语匹配率高。
     *
     * <p>使用 {@code match} 而非 {@code term}：query 为自然语言（甚至整段正文），
     * 需交由分词器切分。该路径上精确词匹配没有意义。
     *
     * <p>失败时返回空列表：历史索引中该字段可能为 index=false（建索引时仅为向量准备），
     * 此时查询返回空且不报错，因此 {@link #ensureIndex} 会在启动阶段拦截该情况。
     */
    private List<ChunkHit> keywordHits(Long novelId, String query, int size) {
        try {
            SearchResponse<ObjectNode> response = elasticsearchClient.search(request -> request
                    .index(INDEX_NAME)
                    .size(size)
                    .query(q -> q.bool(bool -> bool
                            .filter(filter -> filter.term(term -> term
                                    .field("novelId").value(novelId)))
                            .must(must -> must.match(match -> match
                                    .field(FIELD_TEXT).query(query)))))
                    .source(source -> source.filter(field -> field
                            .includes("chapterId", "chapterNo", "seq", "text"))),
                    ObjectNode.class);
            return toHits(response);
        } catch (Exception e) {
            log.warn("章节检索：关键词那一路失败，本次只用向量那一路：novelId={}", novelId, e);
            return List.of();
        }
    }

    /** 两条检索路径返回结构一致，共用该转换逻辑 */
    private List<ChunkHit> toHits(SearchResponse<ObjectNode> response) {
        List<ChunkHit> hits = new ArrayList<>();
        for (Hit<ObjectNode> hit : response.hits().hits()) {
            ObjectNode source = hit.source();
            if (source == null) {
                continue;
            }
            hits.add(new ChunkHit(
                    source.path("chapterId").asLong(),
                    source.path("chapterNo").asInt(),
                    source.path("seq").asInt(),
                    source.path("text").asText(),
                    hit.score() == null ? 0 : hit.score()));
        }
        return hits;
    }

    /**
     * 混合检索时每一路的候选数。
     *
     * <p>融合本质是重排：某条结果仅在关键词一路排第 5、未进入向量榜，仍会获得分数。
     * 因此候选数必须大于最终返回数；若仅取 topK 条，会提前丢弃另一路可召回的结果，
     * 融合失去意义。
     */
    private int candidateSize(int topK) {
        return Math.min(Math.max(topK * CANDIDATE_MULTIPLIER, MIN_CANDIDATES), MAX_TOP_K * 2);
    }

    /**
     * 端口实现（供 ai 模块使用）：转调本服务的检索，仅做类型转换。
     *
     * <p>不直接让 ai 模块使用 {@link ChapterVectorService.ChunkHit} 的原因：那会使 ai 模块依赖
     * search 包类型，端口失去意义；包依赖环在编译期即成立，无法通过「只传一次」规避。
     */
    @Override
    public List<RetrievedChunk> searchRelevant(Long novelId, String query, int topK) {
        return search(novelId, query, topK).stream()
                .map(hit -> new RetrievedChunk(hit.chapterId(), hit.chapterNo(), hit.seq(),
                        hit.text(), hit.score()))
                .toList();
    }

    @Override
    public int reindexNovel(Long novelId) {
        if (novelId == null) {
            return 0;
        }
        ensureIndex();
        long pageSize = PageParam.MAX_PAGE_SIZE;
        int total = 0;
        long pageNo = 1;
        while (true) {
            // 页大小取服务端上限，并以该上限值判断末页：
            // 若请求值大于上限再用请求值比较，「本页不满」将恒为真，导致分页提前结束
            PageResult<ChapterVO> page = chapterService.pageChapterMetaByNovel(novelId, pageNo, pageSize);
            List<ChapterVO> list = page.getList() == null ? List.of() : page.getList();
            if (list.isEmpty()) {
                break;
            }
            for (ChapterVO vo : list) {
                total += indexChapter(novelId, vo.getId());
            }
            if (list.size() < pageSize) {
                break;
            }
            pageNo++;
        }
        log.info("章节向量索引：novelId={} 全量重建完成，共 {} 块", novelId, total);
        return total;
    }

    @Override
    public long count() {
        try {
            return elasticsearchClient.count(request -> request.index(INDEX_NAME)).count();
        } catch (Exception e) {
            log.warn("章节向量索引：统计块数失败", e);
            return -1;
        }
    }

    /** 删除该章 seq >= fromSeq 的分块（fromSeq=0 表示全部删除） */
    private void deleteChunks(Long chapterId, int fromSeq) {
        try {
            elasticsearchClient.deleteByQuery(request -> request
                    .index(INDEX_NAME)
                    .query(query -> query.bool(bool -> bool
                            .must(m -> m.term(term -> term.field("chapterId").value(chapterId)))
                            .must(m -> m.range(range -> range.number(number -> number
                                    .field("seq").gte((double) fromSeq)))))));
        } catch (Exception e) {
            // 清理失败仅残留少量过期分块（检索可能返回，但正文对不上，可人工识别），
            // 不应导致整次索引失败；新分块未写入才是严重问题，会向上抛出异常
            log.warn("章节向量索引：清理 chapterId={} 的旧块失败（seq >= {}）", chapterId, fromSeq, e);
        }
    }

    /** 文档主键：业务键，重复写入同一章直接覆盖 */
    private String chunkId(Long novelId, Long chapterId, int seq) {
        return novelId + "-" + chapterId + "-" + seq;
    }

    /** 读取现有 mapping 的 properties；读取失败返回空 Map（调用方按无法校验则不拦截处理） */
    private Map<String, Object> mappingProperties(IndexOperations ops) {
        try {
            Object properties = ops.getMapping().get("properties");
            if (properties instanceof Map<?, ?> map) {
                Map<String, Object> result = new java.util.HashMap<>();
                map.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
        } catch (Exception e) {
            log.warn("章节向量索引：读取 mapping 失败，跳过一致性校验", e);
        }
        return Map.of();
    }

    /** dense_vector 的维度；读取失败返回 null（不阻断启动） */
    private Integer dimensionsOf(Map<String, Object> properties) {
        if (properties.get(FIELD_VECTOR) instanceof Map<?, ?> vector
                && vector.get("dims") instanceof Number dims) {
            return dims.intValue();
        }
        return null;
    }

    /**
     * 判断 text 字段是否可被关键词检索。
     *
     * <p>判据为「index 未被显式关闭」且「分词器为 IK」。ES mapping 中 {@code index = true}
     * 为默认值会被省略，仅 {@code index = false} 会显式写出，因此判断条件为「不等于 false」。
     */
    private boolean textSearchable(Map<String, Object> properties) {
        if (!(properties.get(FIELD_TEXT) instanceof Map<?, ?> text)) {
            return false;
        }
        if (Boolean.FALSE.equals(text.get("index"))) {
            return false;
        }
        Object analyzer = text.get("analyzer");
        return analyzer == null || String.valueOf(analyzer).startsWith("ik_");
    }

    /** 日志中查询串的截断：查询可能为整段正文，完整输出可读性差 */
    private String abbreviate(String query) {
        String oneLine = query.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }
}
