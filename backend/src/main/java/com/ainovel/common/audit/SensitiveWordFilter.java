package com.ainovel.common.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 敏感词过滤（内容安全的第一道防线）。
 *
 * <p>词表来自配置 {@code app.audit.sensitive-words}，修改词表只需修改配置，不必重新发版。
 *
 * <p><b>作品/章节预审与评论发表共用该词表</b>，内容安全应只有一套口径，
 * 否则会出现「可发布的评论」与「可过审的小说」两套标准。
 *
 * <p>当前实现为 contains 线性匹配，词表规模较小（个位数至几十条）时足够。
 * 词表规模扩大后应改用 DFA/Trie，但届时更合适的方案是接入第三方内容安全服务。
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 默认词表：仅作本地开发与兜底，生产以配置为准 */
    private static final String DEFAULT_WORDS = "赌博,色情,暴力,违法,诈骗";

    private final List<String> words;

    public SensitiveWordFilter(@Value("${app.audit.sensitive-words:" + DEFAULT_WORDS + "}") String configured) {
        this.words = parse(configured);
        log.info("敏感词词表加载完成，共 {} 条", words.size());
    }

    /**
     * 命中检测。
     *
     * @return 命中的词（供提示与审计留痕）；未命中返回 {@code null}
     */
    public String match(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /** 是否命中敏感词 */
    public boolean contains(String text) {
        return match(text) != null;
    }

    /** 兼容中英文逗号、空白与顿号作分隔符 */
    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,，、\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
