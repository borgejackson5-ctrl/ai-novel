package com.ainovel.module.search.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 将一章正文切分为供向量检索使用的分块。
 *
 * <p>不能整章使用单个向量：一章两千多字，向量是整段语义的平均值，
 * 章中同时包含伞骨数量、天气与人物心情等内容，该向量与其中任何一件事都不相似。
 * 检索需要回答「前文哪一段提到了该内容」，粒度过粗即无法定位。
 *
 * <p>也不能切得过碎：一句话一个向量时，检索到的仅是「伞骨一共九根」这类孤立句子，
 * 模型缺少上下文，无法判断是否指向同一对象。因此按 {@link #CHUNK_CHARS} 聚合，
 * 相邻块保留 {@link #OVERLAP_CHARS} 的重叠，使跨切点的内容在前后块中均可命中。
 *
 * <p>切点仅落在句末标点或换行上，与审查用的 {@code ChapterSegmenter} 采用同一规则：
 * 在句子中间切分会产生不完整的分块，既影响检索准确度，也不利于模型理解。
 */
@Component
public class ChapterChunker {

    /**
     * 每块的目标字数。约 5~8 句：足以让模型判断所描述的事项，又不会将两件事混入同一向量。
     *
     * <p>该值未经调参，为常见做法（400 字上下），待评测具备检索命中率指标后再调整。
     * 当前以常量而非配置项实现，原因是尚无数据支持第二个取值。
     */
    private static final int CHUNK_CHARS = 400;

    /** 相邻块的重叠字数，约一句 */
    private static final int OVERLAP_CHARS = 60;

    /** 句末标点（中英文）与换行。小说中的换行通常表示段落结束 */
    private static final String SENTENCE_ENDS = "。！？!?…\n";

    /** 短于该长度的块不单独成块（例如切点恰落在标点之后） */
    private static final int MIN_CHUNK_CHARS = 30;

    /**
     * 切块。
     *
     * @param content 章节正文
     * @return 按顺序排列的块；正文为空时返回空列表
     */
    public List<Chunk> split(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String text = content.strip();
        List<Chunk> chunks = new ArrayList<>();
        int from = 0;
        int seq = 0;
        while (from < text.length()) {
            int end = Math.min(text.length(), from + CHUNK_CHARS);
            if (end < text.length()) {
                end = lastSentenceEnd(text, from, end);
            }
            String piece = text.substring(from, end).strip();
            // 过短的分块并入前一块，否则正文末尾会残留仅数字的分块噪声
            if (piece.length() < MIN_CHUNK_CHARS && !chunks.isEmpty()) {
                Chunk last = chunks.remove(chunks.size() - 1);
                chunks.add(new Chunk(last.seq(), last.text() + piece));
                if (end >= text.length()) {
                    break;
                }
            } else if (!piece.isEmpty()) {
                chunks.add(new Chunk(seq++, piece));
            }
            if (end >= text.length()) {
                break;
            }
            // 下一块从重叠处开始。使用 max(from + 1, ...) 兜住对齐后位置反而回退的情况，
            // 该情况会造成死循环，且表现为 CPU 占用持续满载
            from = Math.max(from + 1, alignToSentenceStart(text, Math.max(end - OVERLAP_CHARS, from + 1)));
        }
        return chunks;
    }

    /** 在 (from, end) 内查找最后一个句末标点并在其后切分；未找到时（超长句）按字数硬切 */
    private int lastSentenceEnd(String text, int from, int end) {
        for (int i = end - 1; i > from; i--) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return end;
    }

    /** 从 pos 起查找下一个句子的起始位置，使重叠部分从完整句子开始 */
    private int alignToSentenceStart(String text, int pos) {
        for (int i = pos; i < text.length(); i++) {
            if (SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0) {
                int next = i + 1;
                while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                    next++;
                }
                return next;
            }
        }
        return pos;
    }

    /**
     * 一个块。
     *
     * @param seq  块序号（从 0 起，同一章内唯一），与章 id 一起构成文档主键
     * @param text 块正文
     */
    public record Chunk(int seq, String text) {
    }
}
