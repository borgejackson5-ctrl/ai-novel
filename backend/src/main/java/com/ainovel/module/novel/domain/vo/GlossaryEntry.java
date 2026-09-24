package com.ainovel.module.novel.domain.vo;

/**
 * 名词表里的一条：给审查用的最小信息。
 *
 * <p>不直接传出实体：审查仅关心「写法」与「首次出现的章号」；同时传出行 id、审计字段
 * 会使调用方逐渐依赖这些内部字段。
 *
 * @param name           标准写法
 * @param firstChapterNo 首次出现的章号（可能为空：历史数据或章节已删除）
 * @param hitCount       被审查到的次数。其含义为**「该名字在多少个被审过的章中出现过」**，
 *                       可用于判断「该写法是否稳定」：若在表中仅出现过一次，
 *                       该次本身可能是模型上报的错写
 */
public record GlossaryEntry(String name, Integer firstChapterNo, Integer hitCount) {

    /** 展示用：「沈青梧（第1章）」。章号缺失（历史数据 / 章节已删除）时仅返回名字 */
    public String display() {
        return firstChapterNo == null ? name : name + "（第" + firstChapterNo + "章）";
    }
}
