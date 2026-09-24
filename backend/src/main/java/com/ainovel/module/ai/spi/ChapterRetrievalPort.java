package com.ainovel.module.ai.spi;

import java.util.List;

/**
 * 章节向量检索端口：AI 审查需按语义检索前文，该能力位于 search 模块（ES 索引在该模块）。
 *
 * <p>**走端口而非直接依赖 search 模块的原因**：search 模块已依赖 ai
 * （`AiSearchServiceImpl` 需调用模型解析搜索意图），ai 再依赖 search 将构成**模块环**，
 * `ModularityTests` 会直接拦截（曾拦截到：`module.ai -> module.search -> module.ai`）。
 * 依赖方向只能单向，因此由 ai 定义所需能力、由 search 实现，与 novel 模块的 `spi/` 采用同一做法。
 *
 * <p>**实现方必须保证不抛异常**：检索是审查链路上的辅助能力，其失败不应导致整章审查失败。
 * 调用方（审查工具）在取不到结果时会退回「当前章 ±10 章」的邻近检索，
 * 因此此处返回空列表的含义是「本次未查到」，而非「该作品无相关内容」。
 */
public interface ChapterRetrievalPort {

    /**
     * 检索某本书中与查询相关的片段。
     *
     * <p>实现侧为**混合检索**（向量 + 关键词，RRF 融合）：两路的盲区不同，
     * 向量按语义查找（「伞的骨架是几根」这种不含原词的说法也能命中），
     * 关键词按字面查找（专名、术语命中准确）。在真实长篇上，
     * 「检索返回的若干条中至少有一条可作依据」的比例明显高于单用任何一路。
     *
     * @param novelId 作品 id（**仅在该书内检索**：跨书会将他人设定当作本书前文）
     * @param query   自然语言查询，可以是「那把刀有多长」这类不含原词的说法
     * @param topK    最多返回的条数
     * @return 按相关度降序；检索不可用或未命中时返回空列表
     */
    List<RetrievedChunk> searchRelevant(Long novelId, String query, int topK);

    /**
     * 一条命中的片段。
     *
     * @param chapterNo 章号，报告需写明「前文第 N 章作 X」，据此定位
     * @param score     相关度。**量纲会变化**：单路检索时为该路的原始分
     *                  （余弦相似度 / BM25），混合检索时为 RRF 融合分。
     *                  该值仅适用于观察排名与调参，**不能作为固定阈值判断依据**：
     *                  余弦下 Top1 与 Top2 常常仅相差 0.01~0.02，分数高低无法区分对错
     */
    record RetrievedChunk(Long chapterId, Integer chapterNo, int seq, String text, double score) {
    }
}
