package com.ainovel.module.ai.service;

import com.ainovel.module.ai.tool.ChapterReviewTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词的守门测试。
 *
 * <p>提示词属于产品文案，修改它的代价不像修改代码那样立即暴露（改坏了只是审查效果变差，
 * 单测全绿、日志干净）。该测试只约束两类内容：
 * <ol>
 *   <li>提示词中点名的工具必须真实存在：改了工具方法名而忘记改提示词时，
 *       模型会调用一个不存在的工具，表现为「审查不再核对前文」，很难排查到此处；</li>
 *   <li>支撑评测指标的若干约束不能被静默删除：见 {@link ChapterReviewPrompt} 的类注释，
 *       每一条都对应一次验证。这几条删除后指标会回落，但没有任何报错。</li>
 * </ol>
 *
 * <p>刻意写得很浅：不比对全文、不做措辞断言。那样一改文案就得同步改测试，
 * 难以维护，最终会被注释掉。
 */
class ChapterReviewPromptTest {

    private static final String PROMPT = ChapterReviewPrompt.SYSTEM_PROMPT;

    @Test
    @DisplayName("提示词点名的工具必须真实存在（改工具名时提示词要同步）")
    void namedToolsExist() {
        List<String> names = Arrays.stream(ChapterReviewTools.class.getMethods())
                .map(Method::getName)
                .toList();
        for (String tool : List.of("searchWordNearby", "readChapter", "listChapters")) {
            if (!PROMPT.contains(tool)) {
                continue;   // 提示词没提这个工具，就不必校验
            }
            assertTrue(names.contains(tool),
                    "提示词让模型调用 " + tool + "，但 ChapterReviewTools 里没有这个方法 —— "
                            + "模型会调一个不存在的工具，审查静默地不再核对前文");
        }
    }

    @Test
    @DisplayName("四类问题名一个都不能少（归一化靠它们收敛）")
    void fourKindsPresent() {
        for (String kind : List.of("错别字", "语病", "标点", "前后不一致")) {
            assertTrue(PROMPT.contains(kind), "提示词里缺了「" + kind + "」这一类");
        }
    }

    @Test
    @DisplayName("撑住指标的约束还在：查前文 / 写法看表 / 以先出现为准 / 只报一次 / excerpt 原样 / 没查成要写明 / 只回 JSON")
    void keyConstraintsKept() {
        assertTrue(PROMPT.contains("必须动手去读前文") && PROMPT.contains("readChapter"),
                "「情节与数字必须动手去读前文」这一段被删了 —— 表接手写法核对之后，"
                        + "模型连情节衔接都不去读了（实测工具调用 16/12 次掉到 0/0）");
        assertTrue(PROMPT.contains("本书已确立的写法"),
                "「写法照表对，不用再查前文」被删了 —— 模型会退回到用工具逐个人名去查前文，"
                        + "把额度与时间花在服务端已经做过的事情上");
        assertTrue(PROMPT.contains("先出现"),
                "「以先出现为准」被删了 —— 模型会把对的那句报成错的，作者照着改就改反了");
        assertTrue(PROMPT.contains("只报一次"),
                "「同一处只报一次」被删了 —— 一个人名出现三次就报三条，清单变噪声");
        assertTrue(PROMPT.contains("原样出现") && PROMPT.contains("8~40"),
                "excerpt 的原样约束被删了 —— 反幻觉核对会失去依据，开始出现编造的片段");
        assertTrue(PROMPT.contains("没能核对"),
                "「工具没查成要如实写明」被删了 —— 实测里模型会把工具报错说成「已核对，没有矛盾」，"
                        + "那是给作者一个假的保证");
        assertTrue(PROMPT.contains("JSON"),
                "「最后一条消息只能是 JSON」被删了 —— 工具调用多了之后模型会回一段自然语言，"
                        + "结构化输出解析失败，整章审查作废（实测发生过）");
        assertTrue(PROMPT.contains("3~5") ,
                "「工具最多查几次」被删了 —— 模型会一章查十几次，把上下文撑爆并拖慢整条链路");
        assertTrue(PROMPT.contains("searchRelevantContext") && PROMPT.contains("全书里翻"),
                "「按语义翻全书」这个工具从提示词里没了 —— 跨章核对会退回到"
                        + "「只能看当前章 ±10 章」，上千章的书里第 800 章对不上第 3 章就查不到");
        assertTrue(PROMPT.contains("前后各 10 章"),
                "要写明邻近检索的范围：不说的话模型不知道该在「想起来词」和「想不起来词」之间怎么选");
        assertTrue(PROMPT.contains("相关前文") && PROMPT.contains("核对依据"),
                "「系统自动给的前文要逐条对照」这条没了 —— 那部分会被模型当成背景介绍读过去，"
                        + "而它正是跨章核对里唯一不依赖模型自觉的部分");
        assertTrue(PROMPT.contains("被量过") && PROMPT.contains("哪怕只有一两个字"),
                "names 段没要求列「被量过的器物」—— 服务端就拿不到「刀」这类候选名词，"
                        + "扫不出「刀有三尺长」，数字核对只剩「模型愿意报 facts」一条路"
                        + "（实测 8 章只攒到 2~3 条）");
        assertTrue(PROMPT.contains("没被量过的普通器物"),
                "「别列没被量过的普通词」这条没了 —— 表里会混进「灯/纸/巷子」，"
                        + "后面每一章都可能拿它们撞出误报");
        assertTrue(PROMPT.contains("names") && PROMPT.contains("不要编"),
                "「顺带列本章专有名词」被删了 —— 作品级名词表就攒不起来，"
                        + "跨章一致性会退回到「靠模型每次自觉去查」");
        assertTrue(PROMPT.contains("facts"),
                "「顺带记本章的设定数字」被删了 —— 数字类跨章矛盾（刀是两尺还是三尺）"
                        + "就还是只能靠模型去读前文");
        assertTrue(PROMPT.contains("发生在什么时候") && PROMPT.contains("每一类都要单独过一遍"),
                "「时间和称谓也要逐条核对」这两条没了 —— 这是跨章的时间点、称谓矛盾唯一的出口。"
                        + "检索那一侧已经排除过：前文片段就摆在提示里（实测把注入条数放大到 10、"
                        + "把第 4 章也注进去），模型四次都没去比；四轮跨章命中恒定在 3/5 ——"
                        + "数字、写法、颜色报得出，时间和称谓一条都没有");
        assertTrue(PROMPT.contains("姓氏与称谓"),
                "「人物的姓氏与称谓」这个维度没了 —— 前面叫「苏姑娘」、后面说「姓舒」"
                        + "这种矛盾就没人管了");
    }

    @Test
    @DisplayName("提示词里不能出现实现细节（模型名 / 接口路径 / 内部字段名）")
    void noImplementationDetails() {
        for (String leak : List.of("deepseek", "Spring AI", "/ai/", "t_chapter", "novelId=")) {
            assertTrue(!PROMPT.toLowerCase().contains(leak.toLowerCase()),
                    "提示词里不该出现实现细节：" + leak);
        }
        assertEquals(PROMPT, PROMPT.trim(), "提示词首尾不要留多余空白（会带进请求体）");
    }
}
