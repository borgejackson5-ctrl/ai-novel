package com.ainovel.module.search.support;

import com.ainovel.module.search.service.ChapterVectorService.ChunkHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RRF 融合的边界测试。
 *
 * <p>这里约束三件「出错不会报错、只会让结果逐渐变差」的事：
 * 两路都命中的没有排到前面（融合即失去意义）、同一块被计算两次（列表中出现重复段落）、
 * 只在一路命中的整条丢弃（另一路的价值随之消失）。
 */
class HybridRankerTest {

    private static final int RRF_K = 10;

    private ChunkHit hit(long chapterId, int seq) {
        return new ChunkHit(chapterId, (int) chapterId, seq, "第" + chapterId + "章的块" + seq, 0.5);
    }

    private String key(ChunkHit hit) {
        return hit.chapterId() + "-" + hit.seq();
    }

    @Test
    @DisplayName("两路都命中的排第一：分数是两段之和 —— 这正是 RRF 想要的效果")
    void bothListsHitRanksFirst() {
        List<ChunkHit> fused = HybridRanker.fuse(List.of(
                List.of(hit(1, 0), hit(2, 0)),
                List.of(hit(1, 0), hit(3, 0))), 3, RRF_K);

        assertEquals(3, fused.size(), "两路共 3 条不同的块，不该出现重复");
        assertEquals(1L, fused.get(0).chapterId(), "两路都命中的第 1 章应该排最前");
        assertEquals("1-0", key(fused.get(0)));
        // 1/(10+1) * 2
        assertEquals(2.0 / 11, fused.get(0).score(), 1e-9);
        // 只在某一处排第 2：1/(10+2)
        assertEquals(1.0 / 12, fused.get(1).score(), 1e-9);
        assertEquals(1.0 / 12, fused.get(2).score(), 1e-9);
    }

    @Test
    @DisplayName("只在一路命中的也要留住 —— 否则混合检索等于只用了其中一路")
    void singleListHitsAreKept() {
        List<ChunkHit> fused = HybridRanker.fuse(List.of(
                List.of(hit(1, 0)),
                List.of(hit(9, 0))), 10, RRF_K);

        assertEquals(2, fused.size());
        // 两路各自的第一名贡献一样，同分时保持先入的顺序（向量那一路在前）
        assertEquals(1L, fused.get(0).chapterId());
        assertEquals(9L, fused.get(1).chapterId());
        assertEquals(1.0 / 11, fused.get(0).score(), 1e-9);
    }

    @Test
    @DisplayName("同一块在两路里都出现 ⇒ 结果里只出现一次（否则 prompt 里会是重复的段落）")
    void deduplicated() {
        List<ChunkHit> fused = HybridRanker.fuse(List.of(
                List.of(hit(1, 0)),
                List.of(hit(1, 0))), 10, RRF_K);

        assertEquals(1, fused.size(), "两路命中的是同一块，结果里只能有一条");
        // 两路各自的第一名：1/11 * 2
        assertEquals(2.0 / 11, fused.get(0).score(), 1e-9);
    }

    @Test
    @DisplayName("一条都没有 / 某一路为空 ⇒ 不炸，另一路照常返回")
    void emptyInputs() {
        assertTrue(HybridRanker.fuse(List.of(), 5, RRF_K).isEmpty());
        assertTrue(HybridRanker.fuse(List.of(List.of()), 5, RRF_K).isEmpty());
        assertTrue(HybridRanker.fuse(null, 5, RRF_K).isEmpty());
        assertEquals(1, HybridRanker.fuse(List.of(List.of(), List.of(hit(1, 0))), 5, RRF_K).size());
    }

    @Test
    @DisplayName("topK 截断，并保持按融合分数降序")
    void topKLimit() {
        List<ChunkHit> fused = HybridRanker.fuse(List.of(
                List.of(hit(1, 0), hit(2, 0), hit(3, 0)),
                List.of(hit(2, 0), hit(3, 0), hit(4, 0))), 2, RRF_K);

        assertEquals(2, fused.size());
        // 第 2、3 章两路都命中（各得 1/11 + 1/12），排在第 1 章（只在一路排第 1，1/11）之前
        assertEquals(2L, fused.get(0).chapterId());
        assertEquals(3L, fused.get(1).chapterId());
    }

    @Test
    @DisplayName("rrfK 取小值才有区分度：k=60（文献默认）时前两名的分数几乎一样")
    void smallRrfKGivesSeparation() {
        List<ChunkHit> with10 = HybridRanker.fuse(List.of(List.of(hit(1, 0), hit(2, 0))), 2, 10);
        List<ChunkHit> with60 = HybridRanker.fuse(List.of(List.of(hit(1, 0), hit(2, 0))), 2, 60);

        double ratio10 = with10.get(0).score() / with10.get(1).score();
        double ratio60 = with60.get(0).score() / with60.get(1).score();

        // 候选只有十几条时用 60，排名信息基本被抹平（62/61 ≈ 1.016）
        assertTrue(ratio10 > 1.05, "k=10 时第一名的优势应该看得出来，实际比 " + ratio10);
        assertTrue(ratio60 < 1.03, "k=60 时前两名几乎同分，这正是不能照抄文献默认值的原因");
        assertEquals(12.0 / 11, ratio10, 1e-9);
    }
}
