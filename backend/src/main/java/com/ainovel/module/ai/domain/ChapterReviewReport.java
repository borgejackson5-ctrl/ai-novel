package com.ainovel.module.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 章节审查结果，即大模型**结构化输出**的目标类型（{@code ChatClient.call().entity(...)}）。
 *
 * <p>不采用「模型返回自然语言后由服务端正则提取」的原因：审查结果需逐条展示、逐条定位，
 * 从自然语言中提取结构本身即为易错环节。由模型直接输出该结构，再经框架绑定的转换器
 * 生成 JSON 格式说明并解析回对象，为本版本采用的做法（见学习清单 4.2）。
 *
 * <p>但结构化输出**不保证内容可信**：模型仍可能编造正文中不存在的 excerpt。
 * 因此 {@code excerpt} 在使用前会回正文核对（找不到即丢弃并计数），
 * 见 {@code ChapterReviewServiceImpl} 的幻觉过滤。
 *
 * <p>**该类为普通类而非 record**（本项目其他可变载体均为 record）：
 * 模型返回的 JSON 会**将同一字段写出两次**。对 record 而言并非「后者覆盖前者」：
 * Jackson 会收集全部属性后才构造 record，因此在**最后一个字段**遇到重复键时对象已构造完成，
 * 框架会尝试调用 setter，而 record 没有 setter，直接抛出
 * {@code Should never call `set()` on setterless property}，导致**整章审查作废**。
 * 曾出现：评测中 c6 因此作废一章（{@code eval/reports/fact2.json} 的 ok=false）；
 * 当时 {@code facts} 为最后一个字段，而在此之前 {@code names} 一直位于该位置，该缺陷持续存在。
 * 改为带 setter 的类后，重复键退化为「以后一个为准」，该结果可以接受。
 * 守门测试见 {@code ChapterReviewReportTest}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChapterReviewReport {

    /** 一句话总评（作者首先查看该字段） */
    private String summary;

    /** 问题清单，无问题时为空数组 */
    private List<Issue> issues;

    /**
     * 本章出现的专有名词（人名/地名/门派/功法/道具）。服务端据此累积「作品级名词表」，
     * 自下一章起即可用表核对写法，跨章一致性因此不再依赖模型每次主动查询前文。
     * 为空仅表示少记录几个名字，不影响本章的任何结论。
     */
    private List<String> names;

    /**
     * 本章出现的**可核对数字**，写成「名词=数值」的短串（如 {@code 伞骨=七根}）。
     * 服务端据此累积「作品级设定表」，用于核对「前文写七根、本章写九根」这类矛盾。
     * 该类比对原先完全依赖模型阅读前文，而名词表上线后模型不再倾向于查询前文。
     * 为空仅表示少若干条依据。
     */
    private List<String> facts;

    /**
     * 一条问题。
     *
     * <p>同样为普通类而非 record，理由见类注释（模型会将字段写出两次，
     * 而 {@code suggestion} 为该类的最后一个字段）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Issue {

        /** 问题类型：错别字 / 语病 / 标点 / 前后不一致 */
        private String type;

        /** 正文里**原样出现**的片段（会回正文核对，核对不上的丢弃） */
        private String excerpt;

        /** 修改建议 */
        private String suggestion;
    }
}
