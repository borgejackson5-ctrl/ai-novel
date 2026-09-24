package com.ainovel.module.ai.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.ai.client.ChapterReviewRequest;
import com.ainovel.module.ai.client.ChapterReviewRequest;
import com.ainovel.module.ai.client.ChapterReviewer;
import com.ainovel.module.ai.spi.ChapterRetrievalPort;
import com.ainovel.module.ai.domain.ChapterReviewReport;
import com.ainovel.module.ai.domain.ChapterReviewResult;
import com.ainovel.module.ai.domain.entity.AiConfig;
import com.ainovel.module.ai.domain.vo.ChapterReviewVO;
import com.ainovel.module.ai.service.impl.ChapterReviewServiceImpl;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelGlossaryService;
import com.ainovel.module.novel.service.NovelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.anyInt;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 章节审查的业务规则单测。
 *
 * <p>这里刻意不测试「模型答得准不准」（那取决于提示词与模型），只测试代码这一侧的规则：
 * 编造的原文有没有被丢弃、失败有没有与「没问题」区分开、失败有没有退还额度、权限有没有拦住。
 * 「工具调用与结构化输出的链路本身能否跑通」另由 {@code SpringAiChapterReviewerTest}
 * 使用假模型服务验证。
 */
@ExtendWith(MockitoExtension.class)
class ChapterReviewServiceTest {

    private static final String BODY = "他推开窗，外面下着雨。她的心情很沉重要。";

    @Mock
    private ChapterService chapterService;

    @Mock
    private NovelService novelService;

    @Mock
    private AiConfigService aiConfigService;

    @Mock
    private ChapterReviewer chapterReviewer;

    @Mock
    private NovelGlossaryService glossaryService;

    @Mock
    private ChapterRetrievalPort chapterRetrievalPort;

    private ChapterReviewService service;

    @BeforeEach
    void init() {
        // 切段器不 mock：它没有外部依赖，字段带默认值（4000 字）在无 Spring 上下文时同样成立。
        // 使用真实现验证「长章确实被切成多段、短章只调用一次」，比断言 mock 的交互次数更贴近事实。
        service = new ChapterReviewServiceImpl(chapterService, novelService, aiConfigService,
                chapterReviewer, glossaryService, new ChapterSegmenter(), chapterRetrievalPort);
    }

    /** 作者身份 + 平台/自带配置 + 额度估算，一次准备齐全 */
    private AiConfig givenPassableChapter(String content, String pendingContent) {
        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setNovelId(10L);
        chapter.setChapterNo(3);
        chapter.setTitle("第三章");
        chapter.setContent(content);
        chapter.setPendingContent(pendingContent);
        when(chapterService.getById(1L)).thenReturn(chapter);

        Novel novel = new Novel();
        novel.setId(10L);
        novel.setTitle("测试书");
        when(novelService.requireOwnerNovel(10L)).thenReturn(novel);

        // 注意：estimateUnits 是变长参数，按元素匹配（any(String.class)）；
        // 写成 any(), any() 描述的是「两个参数」，生产只传一个时这条 stub 就不会被使用，
        // Mockito 严格模式下会直接报 UnnecessaryStubbing
        when(aiConfigService.estimateUnits(any(String.class))).thenReturn(3000);
        AiConfig config = new AiConfig();
        config.setBaseUrl("http://ai.test/v1");
        config.setApiKey("sk-test");
        config.setModel("deepseek-chat");
        config.setQuotaChargedUnits(3000L);
        when(aiConfigService.getActiveConfigForUser(any(), anyLong())).thenReturn(config);
        return config;
    }

    private ChapterReviewReport report(ChapterReviewReport.Issue... issues) {
        return new ChapterReviewReport("总评", List.of(issues), List.of(), List.of());
    }

    private ChapterReviewReport.Issue issue(String type, String excerpt, String suggestion) {
        return new ChapterReviewReport.Issue(type, excerpt, suggestion);
    }

    @Test
    @DisplayName("正文里核对不上的原文片段会被丢弃（防幻觉），其余保留")
    void review_dropsFabricatedExcerpts() {
        givenPassableChapter(BODY, null);
        when(chapterReviewer.review(any())).thenReturn(report(
                issue("错别字", "她的心情很沉重要", "改成「沉重」"),
                issue("语病", "这句话在正文里根本不存在", "随便改改")));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertTrue(vo.getOk());
            assertEquals(1, vo.getIssues().size(), "编造的原文必须被丢弃，只留核对得上的那条");
            assertEquals(1, vo.getDroppedIssues());
            assertEquals("她的心情很沉重要", vo.getIssues().get(0).getExcerpt());
        }
    }

    @Test
    @DisplayName("模型返回空结构 ⇒ 是「没审成」，不是「没问题」（ok=false）")
    void review_nullReport_isFailureNotPass() {
        givenPassableChapter(BODY, null);
        when(chapterReviewer.review(any())).thenReturn(null);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertEquals(false, vo.getOk(), "把「没审成」当成「没问题」是最危险的静默失败");
            assertTrue(vo.getMessage() != null && !vo.getMessage().isBlank(), "要给用户一句能看懂的解释");
        }
    }

    @Test
    @DisplayName("调用失败 ⇒ 退回额度（作者不该为失败的调用买单）")
    void review_failure_refundsQuota() {
        AiConfig config = givenPassableChapter(BODY, null);
        when(chapterReviewer.review(any())).thenThrow(new RuntimeException("模型超时"));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertEquals(false, vo.getOk());
            verify(aiConfigService).refundQuotaIfCharged(config, 9L);
        }
    }

    @Test
    @DisplayName("下发「本次送审正文多少字」，与计费口径（正文长度）一致")
    void review_reportsReviewedChars() {
        givenPassableChapter(BODY, null);
        when(chapterReviewer.review(any())).thenReturn(report());

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertEquals(BODY.length(), vo.getReviewedChars(),
                    "显示给作者的审查字数要与实际送审的正文一致");
            // 计费口径约束在此处：只按正文估算，不含提示词，否则短正文的额度会被提示词占用一大截
            verify(aiConfigService).estimateUnits(BODY);
        }
    }

    @Test
    @DisplayName("变更待审章审的是影子正文（审查发生在发布前，要审作者刚改完的那版）")
    void review_usesPendingContentWhenPresent() {
        givenPassableChapter("这是已经发布的旧版正文，里面没有问题。", "这是作者刚改完的新版正文，里面有个错别字。");
        when(chapterReviewer.review(any())).thenReturn(report());

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            service.reviewChapter(1L);

            ArgumentCaptor<ChapterReviewRequest> captor = ArgumentCaptor.forClass(ChapterReviewRequest.class);
            verify(chapterReviewer).review(captor.capture());
            assertTrue(captor.getValue().userPrompt().contains("作者刚改完的新版正文"),
                    "影子正文优先，否则审的是线上旧版、白扣一次额度");
            assertEquals(10L, captor.getValue().novelId(), "作品 id 要传下去，工具靠它限定数据范围");
            assertEquals(3, captor.getValue().chapterNo());
        }
    }

    @Test
    @DisplayName("不是作者也不是管理员 ⇒ 直接拒绝，不调模型（审查会读正文、会花额度）")
    void review_notOwner_throws() {
        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setNovelId(10L);
        chapter.setContent(BODY);
        when(chapterService.getById(1L)).thenReturn(chapter);
        when(novelService.requireOwnerNovel(10L))
                .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权限操作该作品"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.reviewChapter(1L));

        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(chapterReviewer, never()).review(any());
    }

    @Test
    @DisplayName("章节没正文 ⇒ 直接提示，不白花一次调用")
    void review_blankBody_throws() {
        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setNovelId(10L);
        chapter.setContent("   ");
        when(chapterService.getById(1L)).thenReturn(chapter);
        when(novelService.requireOwnerNovel(10L)).thenReturn(new Novel());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.reviewChapter(1L));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verify(chapterReviewer, never()).review(any());
    }

    @Test
    @DisplayName("平台没配 Key ⇒ 开跑前就说「暂未开放」，不真去打上游"
            + "（否则作者拿到一句「稍后再试」，而重试永远不会成功）")
    void review_noModel_throwsBeforeCallingModel() {
        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setNovelId(10L);
        chapter.setChapterNo(3);
        chapter.setContent(BODY);
        when(chapterService.getById(1L)).thenReturn(chapter);
        when(novelService.requireOwnerNovel(10L)).thenReturn(new Novel());
        doThrow(new BusinessException(ErrorCode.AI_GENERATE_FAIL, "AI 功能暂未开放，请稍后再试"))
                .when(aiConfigService).requireModelAvailable(9L);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.reviewChapter(1L));

            assertEquals(ErrorCode.AI_GENERATE_FAIL, ex.getErrorCode());
            verify(chapterReviewer, never()).review(any());
        }
    }

    // ==================== 切段与归并 ====================

    /** 构造一段「有句号收尾」的长正文，总长度超过切段阈值（4000），会被切成多段 */
    private String longBody(int sentences) {
        String one = "他推开窗，外面下着雨，街上没有一个人。";
        return one.repeat(sentences);
    }

    @Test
    @DisplayName("超长章被切成多段逐段审，同一处问题在两段里重复报时只留一条")
    void review_longChapter_segmentsAndDeduplicates() {
        String body = longBody(400);   // 每句 18 字 × 400 ≈ 7200 字 ⇒ 至少两段
        givenPassableChapter(body, null);

        // 每一段都报同一个「问题」：合并后应当只剩一条（去重键 = 类型 + 片段）
        String repeated = "他推开窗，外面下着雨，街上没有一个人。";
        when(chapterReviewer.review(any())).thenReturn(report(
                issue("标点", repeated, "这句重复出现，删掉一处")));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertTrue(vo.getOk());
            assertTrue(vo.getSegments() > 1, "超过单段上限的章节必须被切成多段，否则一次调用的输入无上限");
            assertEquals(1, vo.getIssues().size(), "相邻段重复报出的同一处问题只应保留一条");
            assertEquals(body.length(), vo.getReviewedChars(), "计费口径仍是整章正文长度");
        }
        // 每段各调用一次模型：约 7200 字按 4000 字上限切成 2 段，就应调用 2 次（而不是「切了段但只审第一段」）
        verify(chapterReviewer, times(2)).review(any());
    }

    @Test
    @DisplayName("短章只调一次模型：切段只针对超长章，普通章不该被切碎")
    void review_shortChapter_singleCall() {
        givenPassableChapter(BODY, null);
        when(chapterReviewer.review(any())).thenReturn(report());

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertEquals(1, vo.getSegments());
            verify(chapterReviewer, times(1)).review(any());
        }
    }

    @Test
    @DisplayName("模型给的类型带括号补充也要收敛到四类：否则同一问题在两段里各报一次就合并不掉")
    void review_typeIsNormalized() {
        String body = longBody(400);
        givenPassableChapter(body, null);
        String repeated = "他推开窗，外面下着雨，街上没有一个人。";
        // 真实模型返回的即为这种带补充说明的类型串（实际出现过「标点错误（中英文标点混用）」）
        when(chapterReviewer.review(any()))
                .thenReturn(report(issue("标点错误", repeated, "改成全角")))
                .thenReturn(report(issue("标点错误（中英文标点混用）", repeated, "改成全角")));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertEquals(1, vo.getIssues().size(),
                    "两段各报一次、只是类型措辞不同 —— 不去归一化就会留下两条一模一样的条目");
            assertEquals("标点", vo.getIssues().get(0).getType(),
                    "类型必须收敛到四类之一，界面才上得对颜色");
        }
    }

    @Test
    @DisplayName("四种类型按关键字收敛，且「前后不一致」不会被「重复」两个字抢走")
    void review_normalizesAllFourTypes() {
        String body = longBody(400);
        givenPassableChapter(body, null);
        String repeated = "他推开窗，外面下着雨，街上没有一个人。";
        when(chapterReviewer.review(any()))
                .thenReturn(report(issue("前后不一致（整段内容重复）", repeated, "删掉")))
                .thenReturn(report(issue("语病（成分赘余）", repeated, "删掉")));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertEquals(2, vo.getIssues().size(), "归一化后类型不同，这两条不该被合并");
            assertEquals("前后不一致", vo.getIssues().get(0).getType());
            assertEquals("语病", vo.getIssues().get(1).getType());
        }
    }

    @Test
    @DisplayName("后一段失败 ⇒ 整章算「没审成」，且前面几段扣掉的额度要全退（半章结论更误导人）")
    void review_laterSegmentFails_refundsWholeChapter() {
        String body = longBody(400);
        givenPassableChapter(body, null);
        // 第一段正常、第二段抛异常：半章结论不能作为审查结果下发
        when(chapterReviewer.review(any()))
                .thenReturn(report(issue("标点", "他推开窗，外面下着雨，街上没有一个人。", "改")))
                .thenThrow(new RuntimeException("第二段超时"));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertEquals(false, vo.getOk(), "有一段没审成，整章就不能算审过");
            // 两次扣费都要退回：每一次调用各拿到一个独立的 AiConfig 载体
            verify(aiConfigService, times(2)).refundQuotaIfCharged(any(AiConfig.class), eq(9L));
        }
    }

    @Test
    @DisplayName("全文审查入口：章节被删 ⇒ 记「这一章不存在」而不是抛异常（抛了只会让消息反复重投）")
    void reviewForTask_missingChapter_returnsFailureInsteadOfThrowing() {
        when(chapterService.getById(404L)).thenReturn(null);

        ChapterReviewResult result = service.reviewChapterForTask(404L, 9L, "测试书");

        assertEquals(false, result.ok());
        assertTrue(result.message() != null && result.message().contains("不存在"),
                "原因要写得让作者看得懂：这一章是被删了，不是「审查失败」");
        verify(chapterReviewer, never()).review(any());
    }

    @Test
    @DisplayName("服务端自动把「前文」摆进提示词：只取本章之前的章，后文不当依据")
    void review_injectsRelatedContextFromEarlierChapters() {
        givenPassableChapter(BODY, null);   // 本章是第 3 章
        when(chapterReviewer.review(any())).thenReturn(report());
        when(chapterRetrievalPort.searchRelevant(any(), any(), anyInt())).thenReturn(List.of(
                new ChapterRetrievalPort.RetrievedChunk(9L, 5, 0, "本章之后的章，不该当核对依据", 0.9),
                new ChapterRetrievalPort.RetrievedChunk(8L, 1, 0, "前文：那把刀有三尺长。", 0.8)));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);
            service.reviewChapter(1L);
        }

        ArgumentCaptor<ChapterReviewRequest> captor = ArgumentCaptor.forClass(ChapterReviewRequest.class);
        verify(chapterReviewer).review(captor.capture());
        String prompt = captor.getValue().userPrompt();
        assertTrue(prompt.contains("那把刀有三尺长"),
                "服务端检索到的前文没被摆进提示词 —— 那就又回到「等模型自觉去查」了");
        assertFalse(prompt.contains("本章之后的章"),
                "第 5 章在本章（第 3 章）之后，不是核对依据，还会把后文泄露给模型");
    }

    @Test
    @DisplayName("检索不可用 ⇒ 不注入前文，但审查照常完成（辅助能力故障不能带崩主流程）")
    void review_retrievalFailureDoesNotBreakReview() {
        givenPassableChapter(BODY, null);
        when(chapterRetrievalPort.searchRelevant(any(), any(), anyInt()))
                .thenThrow(new RuntimeException("ES 挂了"));
        when(chapterReviewer.review(any()))
                .thenReturn(report(issue("错别字", "她的心情很沉重要", "改成「沉重」")));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            ChapterReviewVO vo = service.reviewChapter(1L);

            assertTrue(vo.getOk(), "检索挂了不该让作者看到「审查失败」");
            assertEquals(1, vo.getIssues().size());
        }

        ArgumentCaptor<ChapterReviewRequest> captor = ArgumentCaptor.forClass(ChapterReviewRequest.class);
        verify(chapterReviewer).review(captor.capture());
        assertFalse(captor.getValue().userPrompt().contains("系统自动找出的相关前文"),
                "检索失败时不该留下半截注入段");
    }
}
