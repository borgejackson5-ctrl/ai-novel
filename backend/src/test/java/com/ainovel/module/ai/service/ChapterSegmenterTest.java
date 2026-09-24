package com.ainovel.module.ai.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切段器的单测（对应 map 侧）。
 *
 * <p>这里约束两条不会报错、只会逐渐劣化的性质：
 * <ol>
 *   <li>不丢字：各段拼接回去必须与原文完全一致。少一段或重叠一段都不会抛异常，
 *       表现为「某些段落就是审不出来」，而作者无从判断是模型的问题还是切段的问题；</li>
 *   <li>不切断句子：切点必须落在句末标点或换行上。被劈开的半句会被模型判出
 *       实际不存在的语病，又因为半句确实在正文中而通不过反幻觉过滤，凭空多出一条误报。</li>
 * </ol>
 */
class ChapterSegmenterTest {

    /** 不走 Spring，直接使用字段默认值（4000）：默认值即线上默认值，测试它才有意义 */
    private final ChapterSegmenter segmenter = new ChapterSegmenter();

    private static final String SENTENCE = "他推开窗，外面下着雨，街上没有一个人。";

    @Test
    @DisplayName("短正文不切：一章三千字本来就该一次审完，切了反而割断跨段判断")
    void shortBody_keptWhole() {
        String body = SENTENCE.repeat(50);   // 18 × 50 = 900 字

        List<ChapterSegmenter.Segment> segments = segmenter.split(body);

        assertEquals(1, segments.size());
        assertEquals(1, segments.get(0).no());
        assertEquals(0, segments.get(0).start());
        assertEquals(body.length(), segments.get(0).end());
        assertEquals(body, segments.get(0).text());
    }

    @Test
    @DisplayName("空正文返回空列表（调用方本来就会先拦一次空正文，这里只是不制造意外）")
    void blankBody_noSegments() {
        assertTrue(segmenter.split(null).isEmpty());
        assertTrue(segmenter.split("").isEmpty());
    }

    @Test
    @DisplayName("超长章被切开，且各段拼回去与原文逐字相同（不丢字、不重叠）")
    void longBody_segmentsCoverEverythingExactly() {
        String body = SENTENCE.repeat(600);   // 10800 字 ⇒ 至少三段

        List<ChapterSegmenter.Segment> segments = segmenter.split(body);

        assertTrue(segments.size() >= 3, "超过单段上限的长章必须被切开，实际 " + segments.size() + " 段");
        StringBuilder joined = new StringBuilder();
        for (ChapterSegmenter.Segment s : segments) {
            joined.append(s.text());
        }
        assertEquals(body, joined.toString(), "切段不能丢字或重复，否则有些内容根本不会被审到");

        // 段号必须从 1 连续递增：结果表里靠它标注「问题落在第几段」
        for (int i = 0; i < segments.size(); i++) {
            assertEquals(i + 1, segments.get(i).no());
        }
        // 相邻段首尾相接，不能出现空隙
        for (int i = 1; i < segments.size(); i++) {
            assertEquals(segments.get(i - 1).end(), segments.get(i).start());
        }
        assertEquals(body.length(), segments.get(segments.size() - 1).end());
    }

    @Test
    @DisplayName("切点落在句末：每段都以标点或换行收尾，不会把一句话劈成两半")
    void longBody_neverCutsInsideSentence() {
        String body = SENTENCE.repeat(600);

        List<ChapterSegmenter.Segment> segments = segmenter.split(body);

        String tail = "。！？…!?.”\"』」》";
        for (int i = 0; i < segments.size() - 1; i++) {
            String text = segments.get(i).text();
            char last = text.charAt(text.length() - 1);
            assertTrue(tail.indexOf(last) >= 0 || last == '\n',
                    "第 " + (i + 1) + " 段的结尾是「" + last + "」——切在了句子中间，会凭空多出误报");
        }
    }

    @Test
    @DisplayName("一段长到没有标点时才硬切：宁可切碎也要保证能推进（否则会死循环或空段）")
    void paragraphWithoutSentenceEnd_stillMakesProgress() {
        // 一整段没有任何标点：往回、往前都找不到边界，只能按上限硬切
        String body = "啊".repeat(9000);

        List<ChapterSegmenter.Segment> segments = segmenter.split(body);

        assertTrue(segments.size() >= 2);
        for (ChapterSegmenter.Segment s : segments) {
            assertFalse(s.text().isEmpty(), "不能产出空段：空段会变成一次没有意义的模型调用");
            assertTrue(s.end() > s.start(), "切点必须往前走，否则会死循环");
        }
    }
}
