package com.ainovel.module.novel.service;

import com.ainovel.module.novel.domain.vo.FactConflict;
import com.ainovel.module.novel.domain.vo.GlossaryConflict;
import com.ainovel.module.novel.domain.vo.GlossaryEntry;
import com.ainovel.module.novel.domain.vo.GlossaryFamily;

import java.util.Collection;
import java.util.List;

/**
 * 作品级专有名词表：记录「本书中已确立的写法」，供审查核对跨章一致性。
 *
 * <p>审查一章时，判断「本章人名与前几章是否一致」需要其他章节的材料；
 * 该材料原依赖模型自行调用工具查询（同一章两次执行可出现一次查出、一次漏掉）。
 * 该书已确立的写法记录于本表后，由服务端直接比对，**不依赖模型是否执行该动作**。
 *
 * <p>置于 novel 模块而非 ai 模块：该类数据为作品附属数据（读取 {@code t_novel_glossary}，
 * 按 novel_id 组织），后续「本书人物表」页面同样使用该表。
 */
public interface NovelGlossaryService {

    /** 本书已确立的写法，按首次出现的章号升序。表为空时返回空列表 */
    List<GlossaryEntry> listEntries(Long novelId);

    /**
     * 将表按「写法仅差一字」分组（见 {@link GlossaryFamily}）。
     *
     * <p>调用方用于两处：注入提示词时将两种写法并列（避免模型择一作为唯一标准）、
     * 比对时判定「本书确实使用过两种写法」。**仅对 3~4 字的名字分组**：
     * 2 字词碰撞面过大（「灯市」可与「灯下 / 灯还 / 灯芯 / 灯笼」匹配），分出的族无意义。
     *
     * @return 每族一条，族内按首次出现的章号升序；表为空时返回空列表
     */
    List<GlossaryFamily> listFamilies(Long novelId);

    /**
     * 将本章出现的专有名词记入表。
     *
     * <p>候选由模型一并给出（结构化输出的 {@code names} 字段），此处执行三道过滤：
     * 长度 2~6 个字、**必须在本章正文中原样出现**（编造的名字不入表）、去重。
     * 已在表中的仅累加计数，不覆盖首次章号。
     *
     * @param body       本章正文（用于核对候选是否真实出现过）
     * @param candidates 模型给出的候选（可为空）
     * @return 实际记录的条数（含仅累加计数的）
     */
    int recordNames(Long novelId, Long chapterId, Integer chapterNo, String body,
                    Collection<String> candidates);

    /**
     * 以已有表核对本章正文：找出「与表中某写法长度相同、同一位置仅差一字」的位置。
     *
     * <p>仅执行一项判定，但结果确定：命中项在两处文本中必然真实存在，并给出章号依据。
     * 表为空（本书尚未审查任何一章）时返回空列表，首次审查无基准可比。
     *
     * @return 每处不一致一条（同一写法仅保留一条，出现次数记在 {@code occurrences}）
     */
    List<GlossaryConflict> detectConflicts(Long novelId, String body);

    /**
     * 以表中的设定数字核对本章：同一实体（刀 / 伞骨 / 年龄）本章数值与前文不同时上报一条。
     *
     * <p>与「写法」分开处理，因为数值有其自身情况：{@code 七根} 与 {@code 7 根} 字面不同
     * 但为同一数值，直接比较字符串即产生误报。因此比对前先用
     * {@link com.ainovel.common.util.NumberWords} 归一数值，归一后相等的不上报。
     *
     * <p>数值有**两条来源**，均需保留：
     * <ol>
     *   <li>模型给出的候选（{@code facts}，形如 {@code "伞骨=七根"}），需通过三道校验；
     *   <li>服务端扫描：以**表中已有的名词**在本章正文中查找紧邻的数量短语；
     *       该路径不依赖模型本章是否上报，表中记录过「刀 = 三尺」后，后续章节出现「刀」时均可比对。</li>
     * </ol>
     * 表为空时返回空列表（首次审查无基准）。
     *
     * @param facts 模型给出的候选；无法解析、或值不是纯数量短语（「七颗铜钉」）的直接丢弃
     */
    List<FactConflict> detectFactConflicts(Long novelId, String body, Collection<String> facts);

    /**
     * 将本章的设定数字记入表（校验同 {@link #detectFactConflicts}）。
     *
     * <p>同一实体已记录、但本章为**另一数值**时，**不修改表中的行**：
     * 首次出现的值继续作为基准（以先出现者为准），矛盾已通过
     * {@link #detectFactConflicts} 上报给作者。
     *
     * <p>同样有两条来源：模型上报的 {@code facts}，以及服务端以
     * 「表中已有的名词 + 本章模型上报的专名（{@code names}）」在正文中扫描得到的结果。
     * 后者为覆盖度的关键：模型上报 `facts` 的比例低（8 章仅积累 2~3 条），
     * 而上报本章专有名词是提示词的硬性要求。
     *
     * @param names 本章模型上报的专有名词，作为扫描候选；为空时仅扫描表中已有的名词
     * @return 实际记录的条数（含仅累加计数的）
     */
    int recordFacts(Long novelId, Long chapterId, Integer chapterNo, String body,
                    Collection<String> facts, Collection<String> names);
}
