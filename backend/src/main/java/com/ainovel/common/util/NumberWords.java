package com.ainovel.common.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将「七根」「两尺」「7 根」这类带量词的数值还原为数字。
 *
 * <p><b>该工具的必要性</b>：跨章核对数字时，作者在前文写「七根伞骨」、本章写「7 根伞骨」，
 * 两种写法**字面不同但表示同一个数**，直接比较字符串会产生误报
 * （误报对作者的干扰大于漏报，是该项目中反复出现的判据）。
 * 而「七根」与「九根」才是真正的矛盾。因此比对前需先对数值做归一。
 *
 * <p>仅在**已确定右侧为数字**的上下文中使用（调用方会先行确认），
 * 因此不必处理「一起」「十分」这类「一/十」不表示数量的词。
 */
public final class NumberWords {

    private NumberWords() {
    }

    /** 阿拉伯数字：写「7 根」时中间可能有空格，只取数字那一段 */
    private static final Pattern ARABIC = Pattern.compile("\\d+");

    /** 中文数字：连续的数字与位（十百千），「七」「二十八」「一百零八」都算 */
    private static final Pattern CHINESE = Pattern.compile("[零一二三四五六七八九十百千两]+");

    private static final String CN_DIGITS = "零一二三四五六七八九";

    /** 「两」在中文中表示 2，但不包含在上表中（按 indexOf 取值会错位），需单独处理 */
    private static final char CN_TWO = '两';

    /** 位：下标 + 1 就是它代表的 10 的幂（十→10、百→100、千→1000） */
    private static final String CN_UNITS = "十百千";

    /**
     * 取出第一个数。
     *
     * @return 无法解析出数字（全为量词或写法无法识别）时返回 {@code null}，
     * 调用方据此退化为「按字面比较」，而非按 0 处理
     */
    public static Integer parse(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        Matcher arabic = ARABIC.matcher(s);
        if (arabic.find()) {
            try {
                return Integer.parseInt(arabic.group());
            } catch (NumberFormatException e) {
                // 位数过多（远超正常使用上限）时按无法解析处理
                return null;
            }
        }
        Matcher chinese = CHINESE.matcher(s);
        if (chinese.find()) {
            return chineseToInt(chinese.group());
        }
        return null;
    }

    /**
     * 判断两种写法是否表示同一个数。
     *
     * <p>仅有一侧可解析时返回 {@code false}（视为不同）：采取保守策略，
     * 宁可多提示一次「两处数值不一致，请确认」，也不因无法解析而静默放过。
     */
    public static boolean sameNumber(String a, String b) {
        Integer x = parse(a);
        Integer y = parse(b);
        return x != null && x.equals(y);
    }

    private static Integer chineseToInt(String s) {
        int section = 0;
        int number = 0;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int digit = c == CN_TWO ? 2 : CN_DIGITS.indexOf(c);
            if (digit >= 0) {
                number = digit;
                any = true;
                continue;
            }
            int unit = CN_UNITS.indexOf(c);
            if (unit >= 0) {
                // 「十五」的十前面没有数字，按 1 算
                section += (number == 0 ? 1 : number) * (int) Math.pow(10, unit + 1);
                number = 0;
                any = true;
            }
            // 万/亿等更大单位不处理：书中的数量、尺寸、时长通常到千即可覆盖
        }
        return any ? section + number : null;
    }
}
