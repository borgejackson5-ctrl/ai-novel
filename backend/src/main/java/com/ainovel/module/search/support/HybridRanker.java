package com.ainovel.module.search.support;

import com.ainovel.module.search.service.ChapterVectorService.ChunkHit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将多路检索结果融合为一份排名（RRF：Reciprocal Rank Fusion）。
 *
 * <p>融合的原因：向量一路按语义匹配（无需精确措辞即可命中），
 * 关键词一路按字面匹配（专名、术语命中率高），两者覆盖盲区互补。
 * 在真实长篇数据上，向量 Top3 覆盖 23/26、关键词 Top3 覆盖 15/26，
 * 两者合并为 24/26；另一数据集为 16~17/26 → 21~22/26。
 *
 * <p>使用 RRF 而非加权求和的原因：两路分数不同源、不可比。
 * 一路是余弦相似度（0~1，Top1 与 Top2 仅差 0.014~0.021），另一路是 BM25 分数
 * （无上界，量纲随词频变化）。两者相加或加权时权重只能凭经验设定，且更换数据集即失效。
 * RRF 仅使用排名：某一路将结果排得越靠前，其对总分的贡献越大，与分数本身无关。
 *
 * <p>{@code rrfK} 不取文献默认值 60 的原因：60 面向上千条候选设计，
 * 该规模下 1/(60+1) 与 1/(60+5) 几乎无差别。此处每路仅取十几条候选，
 * 取 60 会抹平排名信息；取 10 时第一名与第五名的贡献差异可以体现。
 */
public final class HybridRanker {

    /** 两路命中同一条时，其分数为两段贡献之和，这是 RRF 的预期效果 */
    private HybridRanker() {
    }

    /**
     * 融合多路结果。
     *
     * @param lists 每一路的结果，必须已按各自的相关度降序排列
     * @param topK  合并后取前几条
     * @param rrfK  RRF 的常数，见类注释
     * @return 按融合分数降序；同一块在多路里都出现时只保留一条
     */
    public static List<ChunkHit> fuse(List<List<ChunkHit>> lists, int topK, int rrfK) {
        if (lists == null || lists.isEmpty() || topK <= 0) {
            return List.of();
        }
        // 同一块在两路均命中是常态（专名类查询尤为明显），因此按「章 + 块序号」去重。
        // LinkedHashMap 同时保留了同分情况下的插入顺序（排序稳定，向量一路先入）
        Map<String, ChunkHit> byKey = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<ChunkHit> hits : lists) {
            if (hits == null) {
                continue;
            }
            int rank = 1;
            for (ChunkHit hit : hits) {
                if (hit == null) {
                    continue;
                }
                String key = hit.chapterId() + "-" + hit.seq();
                byKey.putIfAbsent(key, hit);
                // rank 从 1 起：排第一的贡献 1/(k+1)，排第五的贡献 1/(k+5)
                scores.merge(key, 1.0 / (rrfK + rank), Double::sum);
                rank++;
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> withScore(byKey.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    /** 替换分数：融合后的分数为 RRF 分而非余弦相似度，字段名不变但含义已变化 */
    private static ChunkHit withScore(ChunkHit hit, double score) {
        return new ChunkHit(hit.chapterId(), hit.chapterNo(), hit.seq(), hit.text(), score);
    }
}
