package com.ainovel.module.search.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切块器的边界测试。
 *
 * <p>这里约束的都是不会报错但会劣化的性质：块劈开了句子（检索不准）、
 * 相邻块没有重叠（跨切点的事实两块都找不到）、切点算错导致死循环（占满 CPU）。
 */
class ChapterChunkerTest {

    private static final int CHUNK_CHARS = 400;

    /** 一段约 100 字的正文，句末标点齐全 */
    private static final String PARAGRAPH = "他推开木窗，外面下着细雨。屋檐下的水声一直没停。"
            + "巷子尽头亮着一盏灯，灯芯爆了一下。他没有回头，只把刀往背后一送。"
            + "雨点敲着瓦片，像是有人在数数。远处传来打更的声音，巷子里静得出奇。";

    private final ChapterChunker chunker = new ChapterChunker();

    @Test
    @DisplayName("空正文 ⇒ 空列表，不抛异常")
    void blankContent() {
        assertTrue(chunker.split(null).isEmpty());
        assertTrue(chunker.split("").isEmpty());
        assertTrue(chunker.split("   \n  ").isEmpty());
    }

    @Test
    @DisplayName("短正文 ⇒ 一块")
    void shortContentIsOneChunk() {
        List<ChapterChunker.Chunk> chunks = chunker.split(PARAGRAPH);

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).seq());
        assertTrue(chunks.get(0).text().startsWith("他推开木窗"));
        assertTrue(chunks.get(0).text().endsWith("静得出奇。"));
    }

    @Test
    @DisplayName("长正文 ⇒ 多块，且每块都不超过目标字数")
    void longContentSplits() {
        String text = PARAGRAPH.repeat(10);   // 约 1000 字

        List<ChapterChunker.Chunk> chunks = chunker.split(text);

        assertTrue(chunks.size() >= 3, "约 1000 字至少该切成 3 块，实际 " + chunks.size());
        for (ChapterChunker.Chunk chunk : chunks) {
            assertTrue(chunk.text().length() <= CHUNK_CHARS,
                    "块超长（" + chunk.text().length() + " 字）：切点算错了");
        }
        // 序号连续，且首块从正文开头、末块到正文结尾，中间不能漏内容
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).seq());
        }
        assertTrue(chunks.get(0).text().startsWith("他推开木窗"));
        assertTrue(chunks.get(chunks.size() - 1).text().endsWith("静得出奇。"));
    }

    @Test
    @DisplayName("不劈开句子：除最后一块外，每块都以句末标点收尾")
    void doesNotCutInsideSentence() {
        String text = PARAGRAPH.repeat(10);

        List<ChapterChunker.Chunk> chunks = chunker.split(text);

        for (int i = 0; i < chunks.size() - 1; i++) {
            String piece = chunks.get(i).text();
            char last = piece.charAt(piece.length() - 1);
            assertTrue("。！？!?…".indexOf(last) >= 0,
                    "第 " + i + " 块以「" + piece.substring(Math.max(0, piece.length() - 12)) + "」收尾，"
                            + "切点落在句子中间了");
        }
    }

    @Test
    @DisplayName("相邻块有重叠 —— 跨在切点上的那件事，前后两块里都要找得到")
    void adjacentChunksOverlap() {
        String text = PARAGRAPH.repeat(10);

        List<ChapterChunker.Chunk> chunks = chunker.split(text);

        assertTrue(chunks.size() >= 2, "样本不够长，测不出重叠");
        for (int i = 0; i < chunks.size() - 1; i++) {
            String next = chunks.get(i + 1).text();
            String head = next.substring(0, Math.min(8, next.length()));
            assertTrue(chunks.get(i).text().contains(head),
                    "第 " + i + " 块与第 " + (i + 1) + " 块没有重叠（下一块以「" + head + "」开头）");
        }
    }

    @Test
    @DisplayName("整段没有句末标点 ⇒ 按字数硬切，不死循环")
    void veryLongSentenceStillSplits() {
        String text = "啊".repeat(1000);

        List<ChapterChunker.Chunk> chunks = chunker.split(text);

        assertEquals(3, chunks.size(), "1000 字没有标点，按 400 字切应得 3 块");
        assertTrue(chunks.get(0).text().length() <= CHUNK_CHARS);
    }
}
