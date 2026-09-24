package com.ainovel.module.search.service;

import java.util.List;

/**
 * 章节正文的向量索引：将一章切分为块、向量化、写入 ES，并支持按语义检索。
 *
 * <p>该模块解决的问题：AI 章节审查需核对「本章与前几章是否一致」，
 * 原实现为硬编码的「当前章 ±10 章」（{@code ChapterReviewTools#searchWordNearby}）：
 * 对于上千章的作品，第 800 章与第 3 章的设定是否一致无法查到。
 * 向量检索可按语义定位任意章节中的相关片段，且不依赖与原文一致的措辞。
 *
 * <p>与对外搜索（{@code SearchService}）的区别：后者为读者视角（仅含已上架、已过审的作品），
 * 本模块为作者视角（查看自己的作品，含未发布内容）。因此两条路径不能互相复用：
 * 若复用读者视角的过滤，作者将无法检索到刚写入的章节。
 */
public interface ChapterVectorService {

    /** 索引名（对账、运维排查时要按名字找） */
    String INDEX_NAME = "chapter_chunk";

    /**
     * 建索引；已存在时校验维度与 text 的可检索性，不一致直接失败。
     *
     * <p>校验维度的原因：ES 不允许修改已存在字段的维度，不一致时的故障表现为
     * 每次写入均因维度错误失败，而该报错与配置变更位置相距较远。
     * 在启动时显式报错，优于运行时逐个返回 400。
     *
     * <p>校验 text 的原因：混合检索的关键词一路依赖 {@code text} 的倒排索引，
     * 而 analyzer 与 index 同样无法修改。在历史索引上运行新代码时，关键词一路返回 0 条且不报错，
     * 混合检索静默退化为纯向量，指标下降也难以定位。因此同样在启动阶段拦截。
     */
    void ensureIndex();

    /**
     * 把一章切成块、向量化、写进索引。已存在的块被覆盖，多余的旧块被清掉。
     *
     * @return 写入的块数（章节不存在或正文为空时为 0，同时会清掉这一章在索引里的残留）
     */
    int indexChapter(Long novelId, Long chapterId);

    /**
     * 按当前配置检索本书的相关片段（生产调用走这一条）。
     *
     * @param query 自然语言查询（可以是「伞的骨架是几根」这种不含原词的说法）
     * @param topK  最多返回几条
     * @return 按相关度降序；ES 不可用或查不到时返回空列表（调用方必须实现降级路径）
     */
    List<ChunkHit> search(Long novelId, String query, int topK);

    /**
     * 指定检索方式（诊断与评测用：同一次部署里对比三种走法，不必改配置重启）。
     *
     * @param mode 见 {@link Mode}
     */
    List<ChunkHit> search(Long novelId, String query, int topK, Mode mode);

    /**
     * 将一本作品的全部章节重建进索引。
     *
     * <p>用于两个场景：新建索引后的一次性回填，以及索引被误删或维度变更后的重建。
     * 逐章调用 {@link #indexChapter}，因此是幂等的（同 key 覆盖）。
     *
     * @return 写入的块总数
     */
    int reindexNovel(Long novelId);

    /** 索引里的块总数（对账与运维排查用） */
    long count();

    /**
     * 检索方式。
     *
     * <p>{@code AUTO} 为生产路径：由 {@code app.rag.hybrid-enabled} 决定。
     * 另外三种用于诊断与评测：为验证混合检索优于单用向量，
     * 需能在一次部署内运行三种方式，而无需修改配置并重启三轮。
     */
    enum Mode {
        /** 按配置走（默认混合） */
        AUTO,
        /** 只用向量（语义） */
        VECTOR,
        /** 只用关键词（IK 倒排） */
        KEYWORD,
        /** 两路都跑，RRF 融合 */
        HYBRID
    }

    /**
     * 一条命中。
     *
     * @param chapterNo 章号，报告中「前文第 N 章作 X」依赖该字段
     * @param score     相关度：单路检索时为该路的原始分（余弦相似度 / BM25），
     *                  混合检索时为 RRF 融合分（与余弦不同量纲）。
     *                  因此仅适用于查看排名与调参，不能作为固定阈值判断
     *                  （余弦下 Top1 与 Top2 的差值通常仅为 0.01~0.02）
     */
    record ChunkHit(Long chapterId, Integer chapterNo, int seq, String text, double score) {
    }
}
