package com.ainovel.module.novel.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.constant.NovelConstant;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.enums.SerialStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.mq.MqSender;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.ImportResultVO;
import com.ainovel.module.novel.service.impl.NovelImportServiceImpl;
import com.ainovel.common.message.SearchSyncMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 公版书 TXT 导入单测。
 *
 * <p>需要测试的原因：该链路一次性写入几十万字的正文与定价，且只执行一次即入库：
 * 分章正则选错、编码探测错误、整本价算成 0，事后只能靠人工翻书发现。
 * 尤其是「整本价必须 &gt; 0」：算成 0 等于把「整本解锁」变成 0 币买断，直接绕过付费墙。
 */
@ExtendWith(MockitoExtension.class)
class NovelImportServiceTest {

    /** 书名行在首个章节标题之前，按约定应被丢弃 */
    private static final String TWO_CHAPTERS = """
            《测试书》

            第一章 开端
            正文一
            第二章 转折
            正文二
            """;

    /**
     * 首行直接就是章节标题的文本。
     *
     * <p>专供 BOM 用例：BOM 只会出现在文件第一行，若首行是书名（没有章节标记），
     * 剥不剥 BOM 对分章结果毫无影响，那样写出的「BOM 测试」是假的，改坏实现也不会失败。
     * 只有让首个标题位于第一行，BOM 才会真正把它顶掉、导致整章丢失。
     */
    private static final String CHAPTERS_FIRST = """
            第一章 开端
            正文一
            第二章 转折
            正文二
            """;

    private static final Long CATEGORY_ID = 9L;

    @Mock
    private NovelMapper novelMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ChapterService chapterService;
    @Mock
    private NovelService novelService;
    @Mock
    private MqSender mqSender;

    private NovelImportService importService;

    @BeforeEach
    void initService() {
        importService = new NovelImportServiceImpl(novelMapper, chapterMapper, chapterService, novelService, mqSender);
    }

    private MockMultipartFile txt(String text, Charset charset) {
        return new MockMultipartFile("file", "book.txt", "text/plain", text.getBytes(charset));
    }

    private MockMultipartFile utf8(String text) {
        return txt(text, StandardCharsets.UTF_8);
    }

    // ---------- 入参校验 ----------

    @Test
    @DisplayName("空文件 / 未选分类 / 未填书名 → 都拒绝，且不写库")
    void importTxt_validation() {
        assertEquals(ErrorCode.PARAM_ERROR,
                assertThrows(BusinessException.class,
                        () -> importService.importTxt(null, CATEGORY_ID, "书", null, false, false))
                        .getErrorCode());
        assertEquals(ErrorCode.PARAM_ERROR,
                assertThrows(BusinessException.class,
                        () -> importService.importTxt(new MockMultipartFile("file", "a.txt", "text/plain", new byte[0]),
                                CATEGORY_ID, "书", null, false, false))
                        .getErrorCode());
        assertEquals(ErrorCode.PARAM_ERROR,
                assertThrows(BusinessException.class,
                        () -> importService.importTxt(utf8(TWO_CHAPTERS), null, "书", null, false, false))
                        .getErrorCode());
        assertEquals(ErrorCode.PARAM_ERROR,
                assertThrows(BusinessException.class,
                        () -> importService.importTxt(utf8(TWO_CHAPTERS), CATEGORY_ID, "  ", null, false, false))
                        .getErrorCode());

        verify(novelMapper, never()).insert(any(Novel.class));
        verify(mqSender, never()).sendAfterCommit(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("全文没有正文 → 拒绝（而不是导入一本 0 章的书）")
    void importTxt_noContent() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.importTxt(utf8("   \n\t\n  "), CATEGORY_ID, "书", null, false, false));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verify(novelMapper, never()).insert(any(Novel.class));
    }

    // ---------- 幂等与覆盖 ----------

    @Test
    @DisplayName("同名书 + 不覆盖 → 直接跳过，一个字都不写")
    void importTxt_duplicate_skipped() {
        when(novelMapper.selectOne(any())).thenReturn(existing(77L, "旧作者"));

        ImportResultVO vo = importService.importTxt(utf8(TWO_CHAPTERS), CATEGORY_ID, "测试书", null, false, false);

        assertTrue(vo.isSkipped());
        assertEquals("测试书", vo.getTitle());
        verify(novelMapper, never()).insert(any(Novel.class));
        verify(novelMapper, never()).updateById(any(Novel.class));
        verify(chapterMapper, never()).deleteByNovelId(any());
        verify(mqSender, never()).sendAfterCommit(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("同名书 + 覆盖 → 必须物理删旧章节（逻辑删的残留行会占住唯一索引，重建必冲突）")
    void importTxt_overwrite_rebuildsChapters() {
        Novel old = existing(77L, "旧作者");
        when(novelMapper.selectOne(any())).thenReturn(old);

        ImportResultVO vo = importService.importTxt(utf8(TWO_CHAPTERS), CATEGORY_ID, "测试书", "  ", true, false);

        verify(chapterMapper).deleteByNovelId(77L);
        verify(novelMapper, never()).insert(any(Novel.class));
        verify(novelMapper).updateById(old);

        assertFalse(vo.isSkipped());
        assertTrue(vo.isOverwrite());
        assertEquals(77L, vo.getNovelId().longValue(), "覆盖导入必须沿用原 id，否则书架/历史会全部悬空");
        assertEquals(CATEGORY_ID, old.getCategoryId(), "分类没被更新");
        assertEquals("旧作者", old.getAuthor(), "作者留空时应保留原作者，而不是覆盖成「佚名」");
        assertEquals(2, old.getTotalChapters());
        assertEquals(SerialStatusEnum.FINISHED.getCode(), old.getSerialStatus(),
                "公版书一律完本，否则书库里会出现「连载中」的名著");
        assertTrue(old.getFinishTime() != null, "标记完本却没写完结时间，状态自相矛盾");
    }

    @Test
    @DisplayName("覆盖导入会顺带失效详情与目录缓存（否则读者继续读到旧目录）")
    void importTxt_overwrite_evictsCaches() {
        when(novelMapper.selectOne(any())).thenReturn(existing(77L, "旧作者"));

        importService.importTxt(utf8(TWO_CHAPTERS), CATEGORY_ID, "测试书", null, true, false);

        verify(novelService).evictDetailCache(77L);
        verify(chapterService).evictListCache(77L);
    }

    // ---------- 新建导入 ----------

    @Test
    @DisplayName("新建导入 → 章节全部挂到新书 id 上，并投递检索同步消息")
    void importTxt_newNovel() {
        when(novelMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> {
            ((Novel) inv.getArgument(0)).setId(999L);
            return 1;
        }).when(novelMapper).insert(any(Novel.class));

        ImportResultVO vo = importService.importTxt(utf8(TWO_CHAPTERS), CATEGORY_ID, "测试书", null, false, false);

        assertEquals(999L, vo.getNovelId().longValue());
        assertEquals(2, vo.getChapterCount());
        assertFalse(vo.isOverwrite());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Chapter>> cap = ArgumentCaptor.forClass(List.class);
        verify(chapterService).saveBatch(cap.capture(), anyInt());
        assertEquals(2, cap.getValue().size());
        for (Chapter c : cap.getValue()) {
            assertEquals(999L, c.getNovelId(),
                    "章节没挂上书 id 会变成孤儿数据，阅读器里一章都看不到");
            assertEquals(ChapterAuditStatusEnum.PASS.getCode(), c.getAuditStatus(),
                    "公版书应直接置为通过，靠 DB 默认值会随建表脚本变化");
            assertEquals(c.getChapterNo(), c.getSort(), "章序号与排序值应当一致");
        }

        ArgumentCaptor<Object> msgCap = ArgumentCaptor.forClass(Object.class);
        verify(mqSender).sendAfterCommit(eq(MqConstant.SEARCH_EXCHANGE),
                eq(MqConstant.SEARCH_SYNC_ROUTING_KEY), msgCap.capture());
        SearchSyncMessage msg = (SearchSyncMessage) msgCap.getValue();
        assertEquals(999L, msg.getNovelId().longValue(), "同步消息要带 id，消费侧按 id 回查");
        assertEquals("UPSERT", msg.getOperation());
    }

    @Test
    @DisplayName("新建导入：作者留空 → 落成「佚名」，并写入首章正文做简介")
    void importTxt_newNovel_defaultAuthorAndIntro() {
        when(novelMapper.selectOne(any())).thenReturn(null);

        importService.importTxt(utf8(TWO_CHAPTERS), CATEGORY_ID, "测试书", "   ", false, false);

        ArgumentCaptor<Novel> cap = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).insert(cap.capture());
        Novel saved = cap.getValue();
        assertEquals("佚名", saved.getAuthor());
        assertTrue(saved.getIntro() != null && saved.getIntro().contains("正文一"),
                "简介应取首章正文占位，实际：" + saved.getIntro());
        assertEquals(CommonStatusEnum.ENABLED.getCode(), saved.getStatus());
    }

    // ---------- 定价 ----------

    @Test
    @DisplayName("收费书定价：首章免费、其余每章单价，整本价 = 付费章 × 单价 × 折扣且不得为 0")
    void importTxt_pricing_paid() {
        when(novelMapper.selectOne(any())).thenReturn(null);

        importService.importTxt(utf8(TWO_CHAPTERS), CATEGORY_ID, "测试书", null, false, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Chapter>> cap = ArgumentCaptor.forClass(List.class);
        verify(chapterService).saveBatch(cap.capture(), anyInt());
        List<Chapter> chapters = cap.getValue();

        assertEquals(0, chapters.get(0).getUnlockCoin().intValue(), "首章应当免费试读");
        for (int i = 1; i < chapters.size(); i++) {
            assertEquals(NovelConstant.CHAPTER_PRICE, chapters.get(i).getUnlockCoin().intValue(),
                    "第 " + (i + 1) + " 章单价不对");
        }

        ArgumentCaptor<Novel> novelCap = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).insert(novelCap.capture());
        int paidChapters = chapters.size() - 1;
        int expected = (int) Math.round(paidChapters * NovelConstant.CHAPTER_PRICE
                * NovelConstant.BUNDLE_DISCOUNT);
        assertEquals(expected, novelCap.getValue().getCoinPrice().intValue());
    }

    @Test
    @DisplayName("整本价恒 &gt; 0：为 0 等于「整本解锁」0 币买断，直接绕过付费墙")
    void importTxt_bundlePriceNeverZero() {
        // 单章付费线：只要有付费章，整本价就必须 ≥ 1。
        // 用一章的短书逼近「付费章数最少」这一边界。
        String oneTitleTwoChapters = "第一章 甲\n正文\n第二章 乙\n正文\n";
        when(novelMapper.selectOne(any())).thenReturn(null);

        importService.importTxt(utf8(oneTitleTwoChapters), CATEGORY_ID, "测试书", null, false, false);

        ArgumentCaptor<Novel> cap = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).insert(cap.capture());
        assertTrue(cap.getValue().getCoinPrice() > 0,
                "付费书的整本价必须 > 0，否则整本解锁可以 0 币买断");
    }

    @Test
    @DisplayName("整本免费 → 所有章节 0 币、整本价 0（公版名著不该有付费墙）")
    void importTxt_pricing_free() {
        when(novelMapper.selectOne(any())).thenReturn(null);

        importService.importTxt(utf8(TWO_CHAPTERS), CATEGORY_ID, "测试书", null, false, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Chapter>> cap = ArgumentCaptor.forClass(List.class);
        verify(chapterService).saveBatch(cap.capture(), anyInt());
        for (Chapter c : cap.getValue()) {
            assertEquals(0, c.getUnlockCoin().intValue(), "免费书不该有解锁币");
        }

        ArgumentCaptor<Novel> novelCap = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).insert(novelCap.capture());
        assertEquals(0, novelCap.getValue().getCoinPrice().intValue());
    }

    // ---------- 分章与编码 ----------

    @Test
    @DisplayName("分章：中文章节号优先于「1、」这类数字序号（两者都出现时选错会把正文切碎）")
    void splitChapters_chinesePatternWins() {
        String mixed = """
                第一章 开端
                正文一
                1、这行不是章节标题
                正文二
                第二章 转折
                正文三
                """;
        when(novelMapper.selectOne(any())).thenReturn(null);

        ImportResultVO vo = importService.importTxt(utf8(mixed), CATEGORY_ID, "测试书", null, false, false);

        assertEquals(2, vo.getChapterCount(),
                "被「1、」抢走了正则，正文会被误切成一堆小段");
    }

    @Test
    @DisplayName("分章：全文没有章节标记 → 整篇作为一章，标题补「第1章」")
    void splitChapters_noMarker_singleChapter() {
        when(novelMapper.selectOne(any())).thenReturn(null);

        ImportResultVO vo = importService.importTxt(
                utf8("这是一段没有任何章节标记的正文内容。"), CATEGORY_ID, "测试书", null, false, false);

        assertEquals(1, vo.getChapterCount());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Chapter>> cap = ArgumentCaptor.forClass(List.class);
        verify(chapterService).saveBatch(cap.capture(), anyInt());
        assertEquals("第1章", cap.getValue().get(0).getTitle());
        assertEquals(1, cap.getValue().get(0).getChapterNo().intValue());
    }

    @Test
    @DisplayName("章节标题超过 100 字 → 截断（对齐数据库列宽，否则整批入库失败）")
    void splitChapters_titleTruncated() {
        String longTitle = "第一章 " + "甲".repeat(150);
        when(novelMapper.selectOne(any())).thenReturn(null);

        importService.importTxt(utf8(longTitle + "\n正文\n"), CATEGORY_ID, "测试书", null, false, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Chapter>> cap = ArgumentCaptor.forClass(List.class);
        verify(chapterService).saveBatch(cap.capture(), anyInt());
        assertEquals(100, cap.getValue().get(0).getTitle().length());
    }

    @Test
    @DisplayName("编码探测：GBK 文件不按 UTF-8 硬解（否则正文全是乱码且分章全失效）")
    void charset_gbk() {
        when(novelMapper.selectOne(any())).thenReturn(null);

        ImportResultVO vo = importService.importTxt(
                txt(TWO_CHAPTERS, Charset.forName("GBK")), CATEGORY_ID, "测试书", null, false, false);

        assertEquals("GBK", vo.getCharset());
        assertEquals(2, vo.getChapterCount());
    }

    @Test
    @DisplayName("编码探测：带 BOM 的 UTF-8 → 必须剥掉 BOM，否则首个章节标题匹配不上、整章丢失")
    void charset_utf8WithBom() {
        when(novelMapper.selectOne(any())).thenReturn(null);
        // 使用「首行即章节标题」的文本，BOM 才会真正作用在标题上
        byte[] body = CHAPTERS_FIRST.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        MockMultipartFile file = new MockMultipartFile("file", "book.txt", "text/plain", withBom);

        ImportResultVO vo = importService.importTxt(file, CATEGORY_ID, "测试书", null, false, false);

        assertEquals("UTF-8", vo.getCharset(), "带 BOM 应当按 UTF-8 处理");
        assertEquals(2, vo.getChapterCount(),
                "BOM 没剥掉 ⇒ 首行不是章节标题 ⇒ 第一章被整章丢弃");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Chapter>> cap = ArgumentCaptor.forClass(List.class);
        verify(chapterService).saveBatch(cap.capture(), anyInt());
        assertEquals("第一章 开端", cap.getValue().get(0).getTitle(),
                "首章标题里混进了 BOM 字符，或首章根本没识别出来");
        assertFalse(cap.getValue().get(0).getTitle().contains("\uFEFF"));
    }

    @Test
    @DisplayName("对照：同一文本不带 BOM 时分章结果一致（证明差异确实来自 BOM 而不是别的）")
    void charset_utf8WithoutBom_sameResult() {
        when(novelMapper.selectOne(any())).thenReturn(null);

        ImportResultVO vo = importService.importTxt(
                utf8(CHAPTERS_FIRST), CATEGORY_ID, "测试书", null, false, false);

        assertEquals("UTF-8", vo.getCharset());
        assertEquals(2, vo.getChapterCount());
    }

    @Test
    @DisplayName("编码探测：纯 UTF-8 → UTF-8")
    void charset_utf8() {
        when(novelMapper.selectOne(any())).thenReturn(null);

        assertEquals("UTF-8", importService.importTxt(
                utf8(TWO_CHAPTERS), CATEGORY_ID, "测试书", null, false, false).getCharset());
    }

    private Novel existing(Long id, String author) {
        Novel n = new Novel();
        n.setId(id);
        n.setTitle("测试书");
        n.setAuthor(author);
        n.setCategoryId(1L);
        n.setTotalChapters(1);
        n.setWordCount(10L);
        n.setCoinPrice(5);
        n.setReadCount(100L);
        n.setLikeCount(3L);
        return n;
    }
}
