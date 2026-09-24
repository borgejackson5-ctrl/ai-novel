package com.ainovel.module.novel.domain.vo;

import java.util.List;

/**
 * 服务端核对出来的「写法不一致」：本章出现的写法 {@code variant} 与书里已确立的
 * {@code name} 只差一个字。
 *
 * <p>该结果不是模型上报的，而是由名词表比对得到，因此**不依赖模型是否查询前文**。
 * 也正因为来自比对，给出的 {@code excerpt} 必然可在正文中找到（取自原文窗口），
 * 可直接通过反幻觉过滤。
 *
 * <p><b>但不保证哪一方正确</b>：表中的 {@code name} 本身也可能是模型上报的错写。
 * 因此调用方需依据 {@link #siblings()} 与 {@link #hitCount()} 决定措辞：
 * 证据仅支持「两处写法不一致」时不应表述为「此处应改成 X」，否则作者可能将正确写法改错。
 *
 * @param name           书中已确立的写法（族中首选，即最早出现的那条）
 * @param firstChapterNo 该写法首次出现的章号（建议中需写明「前文第 N 章作 X」）
 * @param variant        本章中的写法（疑似写错的那条）
 * @param excerpt        本章原文片段（含上下文，作为报告中的「原句」）
 * @param occurrences    本章中该写法的出现处数（同一处仅上报一次，其余在建议中说明）
 * @param hitCount       表中该写法被审查到的次数（即出现在多少个章中）。为 1 表示仅出现过一次，
 *                       可能是模型上报的错写；≥2 才可靠，可给出确定方向
 * @param siblings       族中除 {@code name} 之外的写法（如「灯心（第6章）」）。非空表示
 *                       本书两种写法均使用过，此时**不应**指定方向，只能提示作者统一
 */
public record GlossaryConflict(String name, Integer firstChapterNo, String variant,
                               String excerpt, int occurrences, int hitCount, List<String> siblings) {
}
