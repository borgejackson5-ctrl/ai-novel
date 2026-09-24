package com.ainovel.module.novel.domain.vo;

import java.util.List;

/**
 * 一族「写法只差一个字」的条目。
 *
 * <p><b>引入「族」的原因</b>：名字由模型一并上报，模型可能将自身写错的写法
 * 也作为专有名词上报（「灯心」「糖胡芦」均曾进入表中）。此时表中可能出现
 * 「灯芯（第 3 章）」与「灯心（第 6 章）」两条，说明**本书中两种写法均被使用过**，
 * 而非「第 6 章写错」。
 *
 * <p>缺少族的概念会产生两个方向相反的问题：
 * <ul>
 *   <li><b>误报</b>：仅以「表中第一条」为标准比对，将先出现但实为错写的条目当作正确写法，
 *       导致作者按错误方向修改；</li>
 *   <li><b>漏报</b>：两种写法均进入表后，比对时「表中已有该写法」的跳过条件会将两种写法
 *       全部拦截，书内确实存在两种写法却始终不上报。</li>
 * </ul>
 *
 * <p>族的成员按**首次出现的章号升序**（由 {@code listEntries} 的排序保证），
 * 所以 {@link #preferred()} 就是「作者第一次写下的那个写法」。
 *
 * @param members 族内条目，至少一条，按首次出现的章号升序
 */
public record GlossaryFamily(List<GlossaryEntry> members) {

    public GlossaryFamily {
        members = List.copyOf(members);
    }

    /** 首选写法：族中最早出现的条目 */
    public GlossaryEntry preferred() {
        return members.get(0);
    }

    /** 本书是否确实使用过多种写法。仅一条时为「唯一写法」，按常规比对即可 */
    public boolean disputed() {
        return members.size() > 1;
    }

    /** 除首选之外的写法，格式化为「灯心（第3章）」这类字符串（用于提示词与报告） */
    public List<String> otherSpellings() {
        return members.stream().skip(1).map(GlossaryEntry::display).toList();
    }
}
