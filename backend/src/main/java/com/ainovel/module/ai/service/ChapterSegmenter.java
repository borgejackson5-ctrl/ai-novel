package com.ainovel.module.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将一章正文切分为若干段，供逐段审查再归并（清单 1.3 map-reduce 的 map 侧）。
 *
 * <p>**不每次都切分**：一章三千字本可一次送审，切分反而会把「前后不一致」
 * 这类需跨段判断的问题人为割断。仅超过阈值的长章才切分，切分之后：
 * <ul>
 *   <li>每段独立送审（段间互不影响，模型仍可通过工具读取全书）；</li>
 *   <li>段结果按「类型 + 片段」去重后合并为一章的报告（见 {@code ChapterReviewServiceImpl}）。</li>
 * </ul>
 *
 * <p>**切点只落在句子或段落边界上**：从上限处向前查找最后一个句末标点/换行；
 * 向前找不到合适的（例如整段对话无句号）则向后查找，宁可使该段略长，
 * 也不将一句话拆为两半：拆开的半句会使模型判出并不存在的语病，
 * 又因该半句确实存在于正文中而无法通过反幻觉过滤，等同于凭空产生一条误报。
 *
 * <p>仅在出现「四千字无一个标点」这类极端输入时才硬切，并记录一条日志。
 */
@Slf4j
@Component
public class ChapterSegmenter {

    /** 句末标点：中英文句号、问号、叹号、省略号，以及成对的收尾引号/书名号 */
    private static final String SENTENCE_END = "。！？…!?.”\"』」》";

    /** 段落分隔 */
    private static final char PARAGRAPH_BREAK = '\n';

    /** 向后查找切点时最多额外查看的字数（优先保证不拆散句子，允许段落略长） */
    private static final int LOOKAHEAD = 200;

    /**
     * 单段上限（字）。超过就切段。
     *
     * <p>带初始值而非仅依赖 {@code @Value}：无 Spring 上下文的单测中不会发生注入，
     * 字段会保持 0，此时 {@code <= 0} 的判据会使每一章都被切成「一段一字」，
     * 单测执行缓慢且不易察觉。带默认值可避免该「测试中保护失效」的情况。
     */
    @Value("${app.ai-review-segment-chars:4000}")
    private int segmentChars = 4000;

    /**
     * 一段正文。
     *
     * @param no    段号，从 1 开始
     * @param start 在整章正文中的起始下标（1.3 中「结果如何定位回原文」的依据）
     * @param end   结束下标（不含）
     * @param text  该段正文
     */
    public record Segment(int no, int start, int end, String text) {
    }

    /**
     * 切段。正文不超过上限时返回单段（这是绝大多数章的情况）。
     */
    public List<Segment> split(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        int limit = segmentChars > 0 ? segmentChars : 4000;
        if (body.length() <= limit) {
            return List.of(new Segment(1, 0, body.length(), body));
        }

        List<Segment> segments = new ArrayList<>();
        int pos = 0;
        int no = 1;
        while (pos < body.length()) {
            int end;
            if (body.length() - pos <= limit) {
                // 剩余部分可一次容纳：不再切分，避免末尾产生仅几十字的碎片段
                end = body.length();
            } else {
                end = boundaryAround(body, pos, pos + limit);
            }
            segments.add(new Segment(no++, pos, end, body.substring(pos, end)));
            pos = end;
        }
        if (segments.size() > 1) {
            log.info("章节正文 {} 字，切成 {} 段审查（单段上限 {}）", body.length(), segments.size(), limit);
        }
        return segments;
    }

    /**
     * 在 {@code limit} 附近找切点：先往回找，找不到再往后找，都没有才硬切。
     *
     * @return 切点（不含），保证大于 {@code from}
     */
    private int boundaryAround(String body, int from, int limit) {
        // ① 向前查找最后一个句末标点/段末：不能一直退到 from，否则该段长度会失控
        int floor = from + (limit - from) / 2;
        for (int i = limit - 1; i >= floor; i--) {
            if (isBoundary(body.charAt(i))) {
                return i + 1;
            }
        }
        // ② 向前未找到合适切点（长段落无标点），改为向后查找：段落略长优于拆散句子
        int lookaheadEnd = Math.min(body.length(), limit + LOOKAHEAD);
        for (int i = limit; i < lookaheadEnd; i++) {
            if (isBoundary(body.charAt(i))) {
                return i + 1;
            }
        }
        // ③ 两端均无边界（极端输入）：硬切并记录日志，静默硬切会使误报缺乏可解释性
        log.warn("章节正文在 {} 字附近找不到句末边界，按上限硬切（可能切断句子）", limit);
        return limit;
    }

    private boolean isBoundary(char c) {
        return c == PARAGRAPH_BREAK || SENTENCE_END.indexOf(c) >= 0;
    }
}
