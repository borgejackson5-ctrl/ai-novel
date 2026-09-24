package com.ainovel.module.novel.domain.vo;

/**
 * 服务端核对得到的「数字不一致」：本章数值与前文记录的不是同一数值。
 *
 * <p>与 {@link GlossaryConflict} 的区别：后者处理写法（「沈青悟」对「沈青梧」），本类处理数字
 * （「九根」对「七根」）。两者刻意分为不同 VO：写法需处理「两种写法并存、不作断言」的
 * 复杂情形，数字则简单得多，数值不同即需作者确认一次。
 *
 * <p>{@code expected} 与 {@code actual} 均保留**正文中的原样写法**（含量词），
 * 上报给作者时原样写回：若输出「7」而作者写的是「七根」，作者会认为上报的不是该句。
 *
 * @param name           被计量的东西（刀 / 伞骨）
 * @param expected       前文使用的数值（首次出现的那次）
 * @param firstChapterNo 前文该数值出现的章号
 * @param actual         本章使用的数值
 * @param excerpt        本章原文片段（取自原文窗口，可直接通过反幻觉核对）
 */
public record FactConflict(String name, String expected, Integer firstChapterNo,
                           String actual, String excerpt) {
}
