package com.ainovel.module.ai.tool;

import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.ai.spi.ChapterRetrievalPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审查工具的守门测试。
 *
 * <p>以下两点均来自实际缺陷：
 * <ol>
 *   <li>工具不能在登录态缺失时抛异常。全文审查运行在 MQ 消费线程中，没有登录上下文，
 *       工具一旦复用「作者视角」那类带归属校验的方法，每次调用都会抛「无权限操作该作品」，
 *       而框架会把异常转成错误提示交给模型，模型照常给出结论：一份未核对前文的自查报告
 *       （甚至写成「已核对，未发现前后矛盾」）。权限在任务创建时已判定，
 *       工具只按 toolContext 中的 novelId 读取。</li>
 *   <li>翻页查找章号不能出现「每本书都只有一页」的情况。服务端会把页大小钳到
 *       {@link PageParam#MAX_PAGE_SIZE}，而「本页不满即最后一页」的判断如果使用请求的页大小比较，
 *       该条件永远成立，第 100 章往后一个都找不到，只返回「本书没有第 N 章」且不报错。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class ChapterReviewToolsTest {

    @Mock
    private ChapterService chapterService;

    @Mock
    private ChapterRetrievalPort chapterRetrievalPort;

    private ChapterReviewTools tools;

    @BeforeEach
    void init() {
        tools = new ChapterReviewTools(chapterService, chapterRetrievalPort);
    }

    private ToolContext ctx(long novelId, int chapterNo) {
        return new ToolContext(Map.of(ChapterReviewTools.CTX_NOVEL_ID, novelId,
                ChapterReviewTools.CTX_CHAPTER_NO, chapterNo));
    }

    private PageResult<ChapterVO> page(long total, List<ChapterVO> list) {
        return PageResult.of(total, 1, PageParam.MAX_PAGE_SIZE, list);
    }

    private ChapterVO meta(long id, int no, String title) {
        ChapterVO vo = new ChapterVO();
        vo.setId(id);
        vo.setChapterNo(no);
        vo.setTitle(title);
        return vo;
    }

    @Test
    @DisplayName("列目录走的是「不做归属校验」的查询：MQ 线程里没有登录态，工具不能抛权限异常")
    void listChapters_usesNonAuthQuery() {
        when(chapterService.pageChapterMetaByNovel(9L, 1, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(2, new ArrayList<>(List.of(meta(11L, 1, "夜雨"), meta(12L, 2, "灯")))));

        String out = tools.listChapters(ctx(9L, 1));

        assertTrue(out.contains("第1章 夜雨") && out.contains("第2章 灯"), out);
        verify(chapterService, never()).pageAuthorVOByNovel(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("找第 150 章要翻到第二页 —— 页大小被服务端钳过，「本页不满」的判断不能拿请求数去比")
    void findChapter_beyondFirstPage() {
        List<ChapterVO> full = new ArrayList<>();
        for (int i = 1; i <= PageParam.MAX_PAGE_SIZE; i++) {
            full.add(meta(1000L + i, i, "第" + i + "章"));
        }
        when(chapterService.pageChapterMetaByNovel(9L, 1, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(150, full));
        when(chapterService.pageChapterMetaByNovel(9L, 2, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(150, new ArrayList<>(List.of(meta(1150L, 150, "第一百五十章")))));
        Chapter chapter = new Chapter();
        chapter.setTitle("第一百五十章");
        chapter.setContent("灯下站着一个人。那人没有回头。");
        when(chapterService.getById(1150L)).thenReturn(chapter);

        String out = tools.readChapter(150, ctx(9L, 150));

        assertTrue(out.contains("第一百五十章"), "第 150 章没找到，实际返回：" + out);
        assertTrue(out.contains("那人没有回头"), out);
    }

    @Test
    @DisplayName("邻近检索：查「沈青」要能同时命中两种写法，并把章号带回去")
    void searchWordNearby_returnsChapterNumbers() {
        when(chapterService.pageChapterMetaByNovel(9L, 1, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(3, new ArrayList<>(List.of(meta(11L, 1, "夜雨"), meta(12L, 2, "灯"),
                        meta(13L, 3, "更声")))));
        Chapter c1 = new Chapter();
        c1.setContent("沈青梧推开木窗。外面下着细雨。");
        when(chapterService.getById(11L)).thenReturn(c1);
        Chapter c2 = new Chapter();
        c2.setContent("沈青悟把刀放在桌上。屋里很静。");
        when(chapterService.getById(12L)).thenReturn(c2);

        String out = tools.searchWordNearby("沈青", ctx(9L, 3));

        assertTrue(out.contains("第1章"), out);
        assertTrue(out.contains("第2章"), out);
        assertTrue(out.contains("沈青梧") && out.contains("沈青悟"), out);
    }

    @Test
    @DisplayName("上下文里没有作品 id ⇒ 直接抛错，不是「没指定就查全部」")
    void missingNovelContext_throws() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tools.listChapters(new ToolContext(Map.of())));
        assertTrue(ex.getMessage().contains("作品上下文"), ex.getMessage());
    }

    @Test
    @DisplayName("查的词太长 ⇒ 明确回一句，不静默查不到")
    void tooLongWord_rejected() {
        String out = tools.searchWordNearby("这是一个特别特别长的要查的词超过了二十个字限制", ctx(9L, 1));
        assertTrue(out.contains("1~20"), out);
        assertFalse(out.contains("没有找到"), "这是参数问题，不该说成「没找到」");
    }

    @Test
    @DisplayName("章不存在时给一句人话，不抛异常（抛出去会让整条消息重试，而重试结果一样）")
    void chapterNotFound_returnsMessage() {
        when(chapterService.pageChapterMetaByNovel(9L, 1, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(0, new ArrayList<>()));

        assertEquals(ChapterReviewTools.NO_MATERIAL_PREFIX + "本书没有第 7 章。",
                tools.readChapter(7, ctx(9L, 1)));
    }

    /**
     * 「没取到材料」的判据必须**只有前缀能触发**。
     *
     * <p>这条是防回归：判据一旦写成关键词匹配（比如 contains("没有找到")），
     * 前文正文里写一句「他没有找到那把刀」就会被误判成查询失败，而那明明是已拿到的材料。
     * 误判的代价是给作者一个假的「核对没做完」，和漏判一样是错的。
     */
    @Test
    @DisplayName("只有统一的那个前缀算「没取到材料」；正文里出现同样的词不算")
    void noMaterialJudgedByPrefixOnly() {
        assertTrue(ChapterReviewTools.isNoMaterial("【本次查询未取到材料】本书没有第 7 章。"));
        // 真实链路中 responseData() 的形态如下：工具返回的字符串被 JSON 再包了一层引号
        // （取自真实请求体，见 isNoMaterial 的说明）。不识别这一层就等于永远判定不出来
        assertTrue(ChapterReviewTools.isNoMaterial("\"【本次查询未取到材料】本书没有第 7 章。\""),
                "ToolResponse 里的字符串带一层 JSON 引号，剥掉后再判前缀");
        assertFalse(ChapterReviewTools.isNoMaterial("\"【第 3 章 夜雨】他没有找到那把刀，只好作罢。\""),
                "这是真实材料，正文里恰好有「没有找到」四个字");
        assertFalse(ChapterReviewTools.isNoMaterial(null));
        assertFalse(ChapterReviewTools.isNoMaterial("【语义检索结果】按相关度从高到低：\n1. 第 2 章：…"));
    }

    /** 八条失败文案（含参数不合规、查无正文、查无命中）都不能漏掉前缀 */
    @Test
    @DisplayName("每条「没取到材料」的返回都带前缀 —— 新增一条失败文案也不会漏判")
    void everyNoMaterialResponseCarriesPrefix() {
        when(chapterService.pageChapterMetaByNovel(9L, 1, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(0, new ArrayList<>()));

        // 目录为空 / 章不存在
        assertTrue(ChapterReviewTools.isNoMaterial(tools.listChapters(ctx(9L, 1))));
        assertTrue(ChapterReviewTools.isNoMaterial(tools.readChapter(7, ctx(9L, 1))));

        // 章存在但没有正文
        when(chapterService.pageChapterMetaByNovel(9L, 1, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(1, new ArrayList<>(List.of(meta(11L, 5, "第五章")))));
        when(chapterService.getById(11L)).thenReturn(chapterWith("   "));
        assertTrue(ChapterReviewTools.isNoMaterial(tools.readChapter(5, ctx(9L, 5))));

        // 参数不合规
        assertTrue(ChapterReviewTools.isNoMaterial(tools.searchWordNearby("", ctx(9L, 5))));
        assertTrue(ChapterReviewTools.isNoMaterial(tools.searchRelevantContext("   ", ctx(9L, 5))));

        // 检索无命中（目录里只有第 5 章，前后 10 章都没有别的章）
        assertTrue(ChapterReviewTools.isNoMaterial(tools.searchWordNearby("沈青", ctx(9L, 5))));
        when(chapterRetrievalPort.searchRelevant(9L, "那把刀有多长", 5)).thenReturn(List.of());
        assertTrue(ChapterReviewTools.isNoMaterial(tools.searchRelevantContext("那把刀有多长", ctx(9L, 5))));

        // 检索结果全出自本章
        when(chapterRetrievalPort.searchRelevant(9L, "那把伞", 5)).thenReturn(List.of(
                new ChapterRetrievalPort.RetrievedChunk(101L, 5, 0, "本章自己的片段", 0.9)));
        assertTrue(ChapterReviewTools.isNoMaterial(tools.searchRelevantContext("那把伞", ctx(9L, 5))));
    }

    @Test
    @DisplayName("语义检索：剔除本章片段（正文已经在 prompt 里），结果带章号")
    void searchRelevantContext_skipsCurrentChapter() {
        when(chapterRetrievalPort.searchRelevant(9L, "那把刀有多长", 5)).thenReturn(List.of(
                new ChapterRetrievalPort.RetrievedChunk(101L, 6, 0, "本章自己的片段，不该再塞回 prompt", 0.9),
                new ChapterRetrievalPort.RetrievedChunk(102L, 1, 0, "那把刀有三尺长，刀鞘是旧的。", 0.8)));

        String out = tools.searchRelevantContext("那把刀有多长", ctx(9L, 6));

        assertTrue(out.contains("第 1 章"), "结果里要带章号（建议里要写「前文第 N 章作 X」）：" + out);
        assertTrue(out.contains("那把刀有三尺长"));
        assertFalse(out.contains("本章自己的片段"), "当前章的片段重复占 prompt，应当剔除");
    }

    @Test
    @DisplayName("语义检索没结果 ⇒ 如实说「没找到」并指出还有哪条路，不能返回空串")
    void searchRelevantContext_emptySaysSo() {
        when(chapterRetrievalPort.searchRelevant(anyLong(), any(), anyInt())).thenReturn(List.of());

        String out = tools.searchRelevantContext("伞骨一共几根", ctx(9L, 3));

        assertTrue(out.contains("没有找到"), "空结果会被模型当成「前文没写过」，必须说清楚是没查到：" + out);
        assertTrue(out.contains("searchWordNearby"), "要告诉模型还能按词查，否则它会放弃核对");
    }

    @Test
    @DisplayName("邻近检索**只翻一遍目录** —— 不能对范围内 20 个章号各从头翻一次")
    void searchWordNearby_readsCatalogOnce() {
        // 300 章、每页 100：范围 140~160 落在第 2 页，翻到「出现 ≥ 160 的章号」即应停止。
        // 若对每个章号各调用一次 findChapterId，而它每次都从第 1 页开始，
        // 这本 300 章的书、20 个章号会产生 20 次以上分页查询（400 章的书约 80 次），
        // 而一次审查中模型会多次调用该工具，代价全花在重复读取同一份目录上。
        when(chapterService.pageChapterMetaByNovel(9L, 1, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(300, chaptersOf(1, 100)));
        when(chapterService.pageChapterMetaByNovel(9L, 2, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(300, chaptersOf(101, 200)));
        when(chapterService.getById(anyLong())).thenReturn(chapterWith("沈青梧推开木窗。"));

        tools.searchWordNearby("沈青", ctx(9L, 150));

        verify(chapterService, times(2))
                .pageChapterMetaByNovel(eq(9L), anyLong(), eq(PageParam.MAX_PAGE_SIZE));
    }

    @Test
    @DisplayName("范围右端之外的章号不该被翻出来：翻到 160 就停，不陪它翻到 300")
    void searchWordNearby_stopsAtRightEnd() {
        when(chapterService.pageChapterMetaByNovel(9L, 1, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(300, chaptersOf(1, 100)));
        when(chapterService.pageChapterMetaByNovel(9L, 2, PageParam.MAX_PAGE_SIZE))
                .thenReturn(page(300, chaptersOf(101, 200)));
        Chapter c = chapterWith("伞骨一共九根。");
        when(chapterService.getById(anyLong())).thenReturn(c);

        tools.searchWordNearby("伞骨", ctx(9L, 150));

        // 第 3 页（201~300）整页都在范围之外，一次都不该取
        verify(chapterService, never()).pageChapterMetaByNovel(eq(9L), eq(3L), anyLong());
    }

    private List<ChapterVO> chaptersOf(int fromNo, int toNo) {
        List<ChapterVO> list = new ArrayList<>();
        for (int no = fromNo; no <= toNo; no++) {
            list.add(meta(1000L + no, no, "第" + no + "章"));
        }
        return list;
    }

    private Chapter chapterWith(String content) {
        Chapter c = new Chapter();
        c.setTitle("某章");
        c.setContent(content);
        return c;
    }

    @Test
    @DisplayName("检索到的片段全出自本章 ⇒ 也要说清楚，别返回一个空清单")
    void searchRelevantContext_onlyCurrentChapter() {
        when(chapterRetrievalPort.searchRelevant(anyLong(), any(), anyInt())).thenReturn(List.of(
                new ChapterRetrievalPort.RetrievedChunk(101L, 3, 0, "本章的片段", 0.9)));

        String out = tools.searchRelevantContext("随便问点什么", ctx(9L, 3));

        assertTrue(out.contains("都出自本章"), "实际返回：" + out);
    }
}
