package com.ainovel.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数值归一化的守门测试。
 *
 * <p>它防止的是误报：跨章核对数字时，作者前文写「七根」、本章写「7 根」，
 * 字面不同但实际是同一个数。不归一就会报出一条「前后不一致」，
 * 而误报对作者的干扰大于漏报（该判据在本项目中反复出现）。
 */
class NumberWordsTest {

    @Test
    @DisplayName("中文数字：单个、带位、带量词都要认")
    void chineseNumbers() {
        assertEquals(7, NumberWords.parse("七根"));
        assertEquals(9, NumberWords.parse("九"));
        assertEquals(2, NumberWords.parse("两尺"));
        assertEquals(28, NumberWords.parse("二十八岁"));
        assertEquals(15, NumberWords.parse("十五"));
        assertEquals(108, NumberWords.parse("一百零八"));
        assertEquals(110, NumberWords.parse("一百一十"));
    }

    @Test
    @DisplayName("阿拉伯数字：带量词、中间有空格都能取到数")
    void arabicNumbers() {
        assertEquals(7, NumberWords.parse("7根"));
        assertEquals(7, NumberWords.parse("7 根"));
        assertEquals(108, NumberWords.parse("108 颗"));
    }

    @Test
    @DisplayName("没数字 / 不认识 → null（调用方据此退化成按字面比，而不是当成 0）")
    void notANumber() {
        assertNull(NumberWords.parse("铜的"));
        assertNull(NumberWords.parse(""));
        assertNull(NumberWords.parse(null));
        assertNull(NumberWords.parse("根"));
    }

    @Test
    @DisplayName("同一个数、两种写法算相同；不同的数算不同")
    void sameNumber() {
        assertTrue(NumberWords.sameNumber("七根", "7根"), "七根 与 7根 是同一个数 —— 报出来就是误报");
        assertTrue(NumberWords.sameNumber("两尺", "2 尺"));
        assertFalse(NumberWords.sameNumber("七根", "九根"));
        assertFalse(NumberWords.sameNumber("七根", "铜的"), "解不出来时保守当不同：宁可多提示一次");
    }
}
