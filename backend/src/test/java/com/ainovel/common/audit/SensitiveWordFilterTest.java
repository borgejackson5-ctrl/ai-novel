package com.ainovel.common.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词过滤单测。
 *
 * <p>需要测试的原因：它是内容安全的第一道闸，且作品/章节预审与评论发表共用同一份词表。
 * 词表解析一旦出现问题（例如分隔符只识别英文逗号，而线上配置写的是中文逗号），
 * 表现为「词表加载成功、日志正常、一条都不拦截」，属于不报错的失效，最难发现。
 */
class SensitiveWordFilterTest {

    private static SensitiveWordFilter of(String configured) {
        return new SensitiveWordFilter(configured);
    }

    // ---------- 分隔符与词表解析 ----------

    @Test
    @DisplayName("词表解析 → 英文逗号 / 中文逗号 / 顿号 / 空白都能当分隔符")
    void parse_supportsAllSeparators() {
        SensitiveWordFilter filter = of("赌博,色情，暴力、诈骗  洗钱");

        assertEquals("赌博", filter.match("他在赌博"));
        assertEquals("色情", filter.match("色情内容"));
        assertEquals("暴力", filter.match("暴力场面"));
        assertEquals("诈骗", filter.match("这是诈骗"));
        assertEquals("洗钱", filter.match("涉嫌洗钱"));
    }

    @Test
    @DisplayName("词表解析 → 去重，且空串/纯分隔符不会留下空词")
    void parse_dedupAndDropEmpty() {
        SensitiveWordFilter filter = of("赌博,赌博,，,,   ,赌博");

        // 只剩一条：证明空词被过滤掉（否则 "" 会让 contains("") 恒为 true，全部内容都被拦）
        assertFalse(filter.contains("完全正常的一段话"), "空词没被过滤掉，导致任意文本都命中");
        assertEquals("赌博", filter.match("赌博"));
    }

    @Test
    @DisplayName("空词表 → 一律不拦（配置写空时的兜底语义）")
    void emptyWordList_neverMatches() {
        assertNull(of("").match("赌博色情暴力"));
        assertFalse(of(null).contains("赌博"));
        assertFalse(of("   ").contains("赌博"));
    }

    // ---------- 命中判定 ----------

    @Test
    @DisplayName("match → 返回命中的词本身（供提示与审计留痕）；未命中返回 null")
    void match_returnsHitWord() {
        SensitiveWordFilter filter = of("赌博,诈骗");

        assertEquals("诈骗", filter.match("这是典型的诈骗话术"));
        assertNull(filter.match("这是一段干净的文本"));
    }

    @Test
    @DisplayName("match → null / 空串 不抛异常")
    void match_nullAndEmpty() {
        SensitiveWordFilter filter = of("赌博");

        assertNull(filter.match(null));
        assertNull(filter.match(""));
    }

    @Test
    @DisplayName("contains 与 match 结论一致（两个入口不能出现两套判断）")
    void contains_agreesWithMatch() {
        SensitiveWordFilter filter = of("赌博");

        for (String text : new String[] {"赌博", "他在赌博", "干净文本", "", "赌"}) {
            assertEquals(filter.match(text) != null, filter.contains(text),
                    "不一致的文本：" + text);
        }
    }

    // ---------- 配置默认值守门 ----------

    @Test
    @DisplayName("配置项名与默认词表：改 key 或清空默认词表都会被这条挡住")
    void valueAnnotationKeepsKeyAndDefaultWordList() {
        Constructor<?> ctor = SensitiveWordFilter.class.getDeclaredConstructors()[0];
        Annotation[][] annotations = ctor.getParameterAnnotations();
        Value value = Arrays.stream(annotations[0])
                .filter(Value.class::isInstance)
                .map(Value.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("构造器参数上没有 @Value —— 词表将无法从配置注入"));

        String expr = value.value();
        assertTrue(expr.startsWith("${app.audit.sensitive-words:"),
                "配置 key 变了，部署时按老 key 配的词表会静默失效。实际：" + expr);
        assertTrue(expr.endsWith("}"), "占位符没写闭合。实际：" + expr);

        String defaults = expr.substring(expr.indexOf(':') + 1, expr.length() - 1);
        // 默认词表是「配置缺失时」的最后一道闸，被清空即内容安全失去防护
        for (String w : new String[] {"赌博", "色情", "暴力", "违法", "诈骗"}) {
            assertTrue(defaults.contains(w),
                    "默认词表丢了「" + w + "」，配置缺失时就不会拦它了。实际默认值：" + defaults);
        }
        assertFalse(new SensitiveWordFilter(defaults).contains("这是一段正常的话"),
                "默认词表本身就让普通文本命中");
    }
}
