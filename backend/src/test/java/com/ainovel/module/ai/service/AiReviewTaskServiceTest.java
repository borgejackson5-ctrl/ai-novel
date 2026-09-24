package com.ainovel.module.ai.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.message.AiReviewMessage;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.ai.dao.AiReviewChapterMapper;
import com.ainovel.module.ai.dao.AiReviewIssueMapper;
import com.ainovel.module.ai.dao.AiReviewTaskMapper;
import com.ainovel.module.ai.domain.ChapterReviewResult;
import com.ainovel.module.ai.domain.entity.AiReviewChapter;
import com.ainovel.module.ai.domain.entity.AiReviewIssue;
import com.ainovel.module.ai.domain.entity.AiReviewTask;
import com.ainovel.module.ai.domain.form.AiReviewStartForm;
import com.ainovel.module.ai.domain.vo.AiReviewIssueVO;
import com.ainovel.module.ai.domain.vo.AiReviewTaskVO;
import com.ainovel.module.ai.service.impl.AiReviewTaskServiceImpl;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.common.mq.MqSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全文审查任务的业务规则单测。
 *
 * <p>测试的是任务的账：派发了哪些章、重复消息会不会被拦下、额度用尽有没有停止、
 * 失败有没有记成「哪几章没审成」、完成判定会不会永远差一章。
 * 「一章怎么审」在 {@code ChapterReviewServiceTest}，这里一律使用替身。
 *
 * <p>刻意不 mock 的一处是幂等判据：它依赖 mapper，这里断言的是
 * 「已经处理过的章不会再调用一次模型」，即「不会重复扣费」。
 */
@ExtendWith(MockitoExtension.class)
class AiReviewTaskServiceTest {

    private static final Long TASK_ID = 100L;
    private static final Long NOVEL_ID = 10L;
    private static final Long USER_ID = 9L;
    private static final Long CHAPTER_ID = 55L;

    @Mock
    private NovelService novelService;

    @Mock
    private ChapterService chapterService;

    @Mock
    private AiReviewTaskMapper taskMapper;

    @Mock
    private AiReviewChapterMapper chapterMapper;

    @Mock
    private AiReviewIssueMapper issueMapper;

    @Mock
    private ChapterReviewService chapterReviewService;

    @Mock
    private AiConfigService aiConfigService;

    @Mock
    private MqSender mqSender;

    private AiReviewTaskService service;

    @BeforeEach
    void init() {
        service = new AiReviewTaskServiceImpl(novelService, chapterService, taskMapper, chapterMapper,
                issueMapper, chapterReviewService, aiConfigService, mqSender);
    }

    private ChapterVO chapter(long id, int no) {
        ChapterVO vo = new ChapterVO();
        vo.setId(id);
        vo.setChapterNo(no);
        vo.setTitle("第" + no + "章");
        vo.setWordCount(3000);
        return vo;
    }

    private AiReviewTask task(int status, int total, int done, int failed) {
        AiReviewTask task = new AiReviewTask();
        task.setId(TASK_ID);
        task.setUserId(USER_ID);
        task.setNovelId(NOVEL_ID);
        task.setNovelTitle("测试书");
        task.setStatus(status);
        task.setTotalChapters(total);
        task.setDoneChapters(done);
        task.setFailedChapters(failed);
        task.setIssueCount(0);
        task.setChargedUnits(0);
        task.setRefundedUnits(0);
        task.setReviewedChars(0);
        return task;
    }

    // ==================== 发起 ====================

    @Test
    @DisplayName("发起：每章派发一条消息（一章一条，崩溃只丢当前章）")
    void start_dispatchesOneMessagePerChapter() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        when(chapterService.listChapterBriefs(NOVEL_ID))
                .thenReturn(List.of(chapter(1L, 1), chapter(2L, 2), chapter(3L, 3)));
        when(novelService.getNovel(NOVEL_ID)).thenReturn(null);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            AiReviewTaskVO vo = service.start(NOVEL_ID, null);

            assertEquals(3, vo.getTotalChapters());
            assertEquals(0, vo.getPercent());
            // taskMapper.selectList 在「有没有在跑的任务」这一步没给 stub，Mockito 默认返回空列表
            verify(taskMapper).insert(any(AiReviewTask.class));
            verify(mqSender, times(3)).sendAfterCommit(anyString(), anyString(), any());
        }
    }

    @Test
    @DisplayName("发起：范围=最近 2 章 ⇒ 只派发尾部两章（长篇一次跑得完，才拿得到完整结论）")
    void start_recentScope_onlyLastN() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        when(chapterService.listChapterBriefs(NOVEL_ID)).thenReturn(List.of(
                chapter(1L, 1), chapter(2L, 2), chapter(3L, 3), chapter(4L, 4)));
        when(novelService.getNovel(NOVEL_ID)).thenReturn(null);

        AiReviewStartForm form = new AiReviewStartForm();
        form.setScope("RECENT");
        form.setRecentCount(2);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            AiReviewTaskVO vo = service.start(NOVEL_ID, form);

            assertEquals(2, vo.getTotalChapters());
            assertEquals(List.of(3L, 4L), dispatchedChapterIds(2));
        }
    }

    @Test
    @DisplayName("补派发不越界：范围为「最近 1 章」的任务，复用补派时不会把整本都派出去")
    void start_reuseScopedTask_staysInScope() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        // 进行中的任务：范围固定在第 4 章（发起时选择的是「最近 1 章」），且这一章尚未处理
        AiReviewTask running = task(AiReviewTask.STATUS_RUNNING, 1, 0, 0);
        running.setScopeFromChapterNo(4);
        running.setScopeToChapterNo(4);
        when(taskMapper.selectList(any())).thenReturn(List.of(running));
        when(chapterService.listChapterBriefs(NOVEL_ID)).thenReturn(List.of(
                chapter(1L, 1), chapter(2L, 2), chapter(3L, 3), chapter(4L, 4)));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            service.start(NOVEL_ID, null);

            // 只补派第 4 章：前 3 章在范围外。若不约束范围，这里会派发 4 条，
            // 后果是 1 章的任务跑完后账面变为 total=1 / done=2
            assertEquals(List.of(4L), dispatchedChapterIds(1));
        }
    }

    @Test
    @DisplayName("整本任务补派发不受范围限制（保住「续跑带上新写的章」这个原有行为）")
    void start_reuseWholeBookTask_dispatchesNewChapters() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        // 没有 scope 字段 = 整本任务
        when(taskMapper.selectList(any()))
                .thenReturn(List.of(task(AiReviewTask.STATUS_RUNNING, 2, 1, 0)));
        when(chapterService.listChapterBriefs(NOVEL_ID)).thenReturn(List.of(
                chapter(1L, 1), chapter(2L, 2), chapter(3L, 3)));
        AiReviewChapter handled = new AiReviewChapter();
        handled.setChapterId(1L);
        handled.setStatus(AiReviewChapter.STATUS_DONE);
        when(chapterMapper.selectList(any())).thenReturn(List.of(handled));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            service.start(NOVEL_ID, null);

            // 第 1 章处理过了，第 2、3 章（含「任务之后新写的」）照旧补上
            assertEquals(List.of(2L, 3L), dispatchedChapterIds(2));
        }
    }

    @Test
    @DisplayName("发起：范围=章号 2~3 ⇒ 只派发这两章（按卷/按区间审）")
    void start_rangeScope_onlyInside() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        when(chapterService.listChapterBriefs(NOVEL_ID)).thenReturn(List.of(
                chapter(1L, 1), chapter(2L, 2), chapter(3L, 3), chapter(4L, 4)));
        when(novelService.getNovel(NOVEL_ID)).thenReturn(null);

        AiReviewStartForm form = new AiReviewStartForm();
        form.setScope("RANGE");
        form.setFromChapterNo(2);
        form.setToChapterNo(3);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            AiReviewTaskVO vo = service.start(NOVEL_ID, form);

            assertEquals(2, vo.getTotalChapters());
            assertEquals(List.of(2L, 3L), dispatchedChapterIds(2));
        }
    }

    @Test
    @DisplayName("发起：章号区间倒置 ⇒ 直接拒绝，不静默审出个空任务")
    void start_rangeScope_inverted_throws() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        when(chapterService.listChapterBriefs(NOVEL_ID))
                .thenReturn(List.of(chapter(1L, 1), chapter(2L, 2)));

        AiReviewStartForm form = new AiReviewStartForm();
        form.setScope("RANGE");
        form.setFromChapterNo(5);
        form.setToChapterNo(2);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.start(NOVEL_ID, form));
            assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("发起：所选范围里没有章节 ⇒ 拒绝（不建一个 0 章的任务）")
    void start_scopeWithNoChapter_throws() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        when(chapterService.listChapterBriefs(NOVEL_ID))
                .thenReturn(List.of(chapter(1L, 1), chapter(2L, 2)));

        AiReviewStartForm form = new AiReviewStartForm();
        form.setScope("RANGE");
        form.setFromChapterNo(100);
        form.setToChapterNo(200);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.start(NOVEL_ID, form));
            assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
            verify(mqSender, never()).sendAfterCommit(anyString(), anyString(), any());
        }
    }

    /** 从派发出去的消息中按顺序取出章节 ID（用于验证「到底审了哪几章」） */
    private List<Long> dispatchedChapterIds(int expectedCount) {
        ArgumentCaptor<AiReviewMessage> captor = ArgumentCaptor.forClass(AiReviewMessage.class);
        verify(mqSender, times(expectedCount)).sendAfterCommit(anyString(), anyString(), captor.capture());
        return captor.getAllValues().stream().map(AiReviewMessage::getChapterId).toList();
    }

    @Test
    @DisplayName("发起：已有进行中的任务 ⇒ 复用它不新建，并补派发没处理完的章")
    void start_reusesRunningTask() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        when(taskMapper.selectList(any())).thenReturn(List.of(task(AiReviewTask.STATUS_RUNNING, 3, 2, 0)));
        when(chapterService.listChapterBriefs(NOVEL_ID))
                .thenReturn(List.of(chapter(1L, 1), chapter(2L, 2), chapter(3L, 3)));
        AiReviewChapter handled = new AiReviewChapter();
        handled.setChapterId(1L);
        handled.setStatus(AiReviewChapter.STATUS_DONE);
        when(chapterMapper.selectList(any())).thenReturn(List.of(handled));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            AiReviewTaskVO vo = service.start(NOVEL_ID, null);

            assertEquals(TASK_ID, vo.getTaskId());
            assertEquals(67, vo.getPercent(), "进度由服务端算好：2/3");
            verify(taskMapper, never()).insert(any(AiReviewTask.class));
            // 1 章处理过 ⇒ 补派发 2、3 两章
            verify(mqSender, times(2)).sendAfterCommit(anyString(), anyString(), any());
        }
    }

    @Test
    @DisplayName("发起：复用进行中任务时会补派发 —— MQ 不可达留下的僵尸任务靠这一步自愈，"
            + "否则用户再点「全文审查」只会拿回那个永远不动的任务，从此建不出新任务")
    void start_reusesRunningTask_redispatchesStuckOne() {
        // 该任务一条消息都没发出过（创建时 MQ 不可达），进度始终停在 0/2
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        when(taskMapper.selectList(any())).thenReturn(List.of(task(AiReviewTask.STATUS_QUEUED, 2, 0, 0)));
        when(chapterService.listChapterBriefs(NOVEL_ID))
                .thenReturn(List.of(chapter(1L, 1), chapter(2L, 2)));
        when(chapterMapper.selectList(any())).thenReturn(List.of());

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            service.start(NOVEL_ID, null);

            verify(taskMapper, never()).insert(any(AiReviewTask.class));
            verify(mqSender, times(2)).sendAfterCommit(anyString(), anyString(), any());
        }
    }

    @Test
    @DisplayName("发起：平台没配 Key ⇒ 开跑前就拒绝，不建一个逐章失败、零产出的任务")
    void start_noModelAvailable_throws() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        doThrow(new BusinessException(ErrorCode.AI_GENERATE_FAIL, "AI 功能暂未开放，请稍后再试"))
                .when(aiConfigService).requireModelAvailable(USER_ID);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.start(NOVEL_ID, null));

            assertEquals(ErrorCode.AI_GENERATE_FAIL, ex.getErrorCode());
            verify(taskMapper, never()).insert(any(AiReviewTask.class));
            verify(mqSender, never()).sendAfterCommit(anyString(), anyString(), any());
        }
    }

    @Test
    @DisplayName("发起：这本书没有章节 ⇒ 拒绝，不建一个永远跑不完的任务")
    void start_noChapters_throws() {
        when(novelService.requireOwnerNovel(NOVEL_ID)).thenReturn(new Novel());
        when(chapterService.listChapterBriefs(NOVEL_ID)).thenReturn(List.of());

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            BusinessException ex = assertThrows(BusinessException.class, () -> service.start(NOVEL_ID, null));
            assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        }
    }

    // ==================== 消费一章 ====================

    @Test
    @DisplayName("消费：消息重投时这一章已审过 ⇒ 不再调一次模型（幂等的意义就是不重复扣费）")
    void processChapter_alreadyHandled_skips() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(AiReviewTask.STATUS_RUNNING, 3, 1, 0));
        AiReviewChapter done = new AiReviewChapter();
        done.setStatus(AiReviewChapter.STATUS_DONE);
        when(chapterMapper.selectOne(any())).thenReturn(done);

        service.processChapter(TASK_ID, CHAPTER_ID);

        verify(chapterReviewService, never()).reviewChapterForTask(anyLong(), anyLong(), any());
        verify(taskMapper, never()).accumulate(anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("消费：任务已中止 ⇒ 队列里剩下的消息直接丢弃（这是取消能立刻生效的原因）")
    void processChapter_taskNotActive_skips() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(AiReviewTask.STATUS_ABORTED, 3, 3, 0));

        service.processChapter(TASK_ID, CHAPTER_ID);

        verify(chapterReviewService, never()).reviewChapterForTask(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("消费：审成 ⇒ 落问题明细、章节标成功、进度按正文字数累加")
    void processChapter_success_recordsIssues() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(AiReviewTask.STATUS_RUNNING, 3, 0, 0));
        when(chapterMapper.selectOne(any())).thenReturn(null);
        when(chapterService.getChapterVO(CHAPTER_ID)).thenReturn(chapter(CHAPTER_ID, 7));
        when(chapterReviewService.reviewChapterForTask(CHAPTER_ID, USER_ID, "测试书"))
                .thenReturn(new ChapterReviewResult(true, null, "总评",
                        List.of(new ChapterReviewResult.Issue(1, "错别字", "沉重要", "改成沉重")),
                        3000, 1, 0, 3000, 0));

        service.processChapter(TASK_ID, CHAPTER_ID);

        verify(issueMapper).insert(any(AiReviewIssue.class));
        ArgumentCaptor<AiReviewChapter> row = ArgumentCaptor.forClass(AiReviewChapter.class);
        verify(chapterMapper).updateById(row.capture());
        assertEquals(AiReviewChapter.STATUS_DONE, row.getValue().getStatus());
        assertEquals(7, row.getValue().getChapterNo(), "章节行要带章号，否则失败明细里说不到第几章");
        verify(taskMapper).accumulate(TASK_ID, 1, 0, 1, 3000, 3000, 0);
    }

    @Test
    @DisplayName("消费：没审成 ⇒ 记成失败并把退回的字数计进账面，不抛异常（重试解决不了模型侧失败）")
    void processChapter_failed_recordsRefund() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(AiReviewTask.STATUS_RUNNING, 3, 0, 0));
        when(chapterMapper.selectOne(any())).thenReturn(null);
        when(chapterService.getChapterVO(CHAPTER_ID)).thenReturn(chapter(CHAPTER_ID, 7));
        when(chapterReviewService.reviewChapterForTask(CHAPTER_ID, USER_ID, "测试书"))
                .thenReturn(ChapterReviewResult.failedAfterRefund("这次审查没能完成", 3000));

        service.processChapter(TASK_ID, CHAPTER_ID);

        verify(issueMapper, never()).insert(any(AiReviewIssue.class));
        ArgumentCaptor<AiReviewChapter> row = ArgumentCaptor.forClass(AiReviewChapter.class);
        verify(chapterMapper).updateById(row.capture());
        assertEquals(AiReviewChapter.STATUS_FAILED, row.getValue().getStatus());
        // done +1、failed +1：done 记录「包含失败在内已处理完成的章数」，failed 是其中的分类计数。
        // 收尾判据只看 done（见 finish_doesNotCountFailureTwice：加上 failed 会导致重复计数）
        verify(taskMapper).accumulate(TASK_ID, 1, 1, 0, 0, 0, 3000);
    }

    @Test
    @DisplayName("消费：额度用尽 ⇒ 中止整个任务（继续跑每章都会失败，只是把同样的错误刷 N 遍）")
    void processChapter_quotaExhausted_abortsTask() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(AiReviewTask.STATUS_RUNNING, 60, 10, 0));
        when(chapterMapper.selectOne(any())).thenReturn(null);
        when(chapterService.getChapterVO(CHAPTER_ID)).thenReturn(chapter(CHAPTER_ID, 11));
        when(chapterReviewService.reviewChapterForTask(CHAPTER_ID, USER_ID, "测试书"))
                .thenThrow(new BusinessException(ErrorCode.AI_QUOTA_EXHAUSTED, "今天的免费字数已经用完了"));

        service.processChapter(TASK_ID, CHAPTER_ID);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(taskMapper).finish(eq(TASK_ID), eq(AiReviewTask.STATUS_ABORTED), message.capture());
        assertTrue(message.getValue().contains("明天"), "要说清「明天还能接着审」，否则作者以为任务废了");
        assertFalse(message.getValue().contains("Key"), "用户可见文案不出现「去配 Key」这类引导");
    }

    @Test
    @DisplayName("消费：章节已被删 ⇒ 记一条「这一章不存在了」，任务照常往下走")
    void processChapter_chapterGone_recordsFailureAndContinues() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(AiReviewTask.STATUS_RUNNING, 3, 0, 0));
        when(chapterMapper.selectOne(any())).thenReturn(null);
        when(chapterService.getChapterVO(CHAPTER_ID))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "章节不存在"));

        service.processChapter(TASK_ID, CHAPTER_ID);

        ArgumentCaptor<AiReviewChapter> row = ArgumentCaptor.forClass(AiReviewChapter.class);
        verify(chapterMapper).updateById(row.capture());
        assertEquals(AiReviewChapter.STATUS_FAILED, row.getValue().getStatus());
        assertTrue(row.getValue().getMessage().contains("不存在"));
        verify(chapterReviewService, never()).reviewChapterForTask(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("消费：最后一章处理完 ⇒ 收尾写终态，进度不会卡在 total-1")
    void processChapter_lastChapter_finishesTask() {
        // 3 章、已处理 2 章，本次是第 3 章：累加后 selectById 返回「已满」的进度
        when(taskMapper.selectById(TASK_ID))
                .thenReturn(task(AiReviewTask.STATUS_RUNNING, 3, 0, 0))
                .thenReturn(task(AiReviewTask.STATUS_RUNNING, 3, 3, 0));
        when(chapterMapper.selectOne(any())).thenReturn(null);
        when(chapterService.getChapterVO(CHAPTER_ID)).thenReturn(chapter(CHAPTER_ID, 3));
        when(chapterReviewService.reviewChapterForTask(CHAPTER_ID, USER_ID, "测试书"))
                .thenReturn(new ChapterReviewResult(true, null, "总评", List.of(), 3000, 1, 0, 3000, 0));

        service.processChapter(TASK_ID, CHAPTER_ID);

        verify(taskMapper).finish(eq(TASK_ID), eq(AiReviewTask.STATUS_DONE), anyString());
    }

    @Test
    @DisplayName("收尾：失败章不能被算两遍 —— 否则一次失败就提前收尾，把剩下的章整批丢掉")
    void finish_doesNotCountFailureTwice() {
        // 2 章的任务，第 1 章失败：真实库中此时 done=1、failed=1（accumulate(1,1,...) 两者均加）。
        // 若判据为 done + failed >= total ⇒ 1+1>=2 会当场判定「已完成」，第 2 章的消息到达时
        // 会被「任务已结束」丢弃，落库只剩 1 章明细（降级冒烟测试中出现过）。
        when(taskMapper.selectById(TASK_ID))
                .thenReturn(task(AiReviewTask.STATUS_RUNNING, 2, 0, 0))
                .thenReturn(task(AiReviewTask.STATUS_RUNNING, 2, 1, 1));
        when(chapterMapper.selectOne(any())).thenReturn(null);
        when(chapterService.getChapterVO(CHAPTER_ID)).thenReturn(chapter(CHAPTER_ID, 1));
        when(chapterReviewService.reviewChapterForTask(CHAPTER_ID, USER_ID, "测试书"))
                .thenReturn(ChapterReviewResult.failed("这一章没审成"));

        service.processChapter(TASK_ID, CHAPTER_ID);

        verify(taskMapper, never()).finish(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("收尾：全部章都处理完（哪怕全失败）才收尾，全失败判 FAILED 而不是「已完成」")
    void finish_onlyWhenEveryChapterHandled() {
        when(taskMapper.selectById(TASK_ID))
                .thenReturn(task(AiReviewTask.STATUS_RUNNING, 2, 0, 0))
                .thenReturn(task(AiReviewTask.STATUS_RUNNING, 2, 2, 2));
        when(chapterMapper.selectOne(any())).thenReturn(null);
        when(chapterService.getChapterVO(CHAPTER_ID)).thenReturn(chapter(CHAPTER_ID, 2));
        when(chapterReviewService.reviewChapterForTask(CHAPTER_ID, USER_ID, "测试书"))
                .thenReturn(ChapterReviewResult.failed("这一章没审成"));

        service.processChapter(TASK_ID, CHAPTER_ID);

        verify(taskMapper).finish(eq(TASK_ID), eq(AiReviewTask.STATUS_FAILED), anyString());
    }

    // ==================== 问题清单的跨章归并 ====================

    @Test
    @DisplayName("问题清单：同一问题出现在三章只占一行，并列出这三章的章号")
    void pageIssues_mergesSameIssueAcrossChapters() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(AiReviewTask.STATUS_DONE, 3, 3, 0));
        when(issueMapper.selectList(any())).thenReturn(List.of(
                issue(1L, 1, "肖战天", "改成「萧战天」"),
                issue(2L, 3, "肖战天", "改成「萧战天」"),
                issue(3L, 9, "肖战天", "改成「萧战天」"),
                issue(4L, 2, "沉重要", "改成「沉重」")));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            PageResult<AiReviewIssueVO> page = service.pageIssues(TASK_ID, 1, 20);

            assertEquals(2L, page.getTotal(), "同一个问题在三章里出现，作者只该看到一条");
            AiReviewIssueVO first = page.getList().get(0);
            assertEquals(3, first.getChapterCount());
            assertEquals("1、3、9", first.getChapterNos());
        }
    }

    private AiReviewIssue issue(Long id, int chapterNo, String excerpt, String suggestion) {
        AiReviewIssue issue = new AiReviewIssue();
        issue.setId(id);
        issue.setTaskId(TASK_ID);
        issue.setChapterId((long) chapterNo);
        issue.setChapterNo(chapterNo);
        issue.setChapterTitle("第" + chapterNo + "章");
        issue.setSegmentNo(1);
        issue.setType("前后不一致");
        issue.setExcerpt(excerpt);
        issue.setSuggestion(suggestion);
        issue.setDedupKey("前后不一致|" + excerpt);
        return issue;
    }

    // ==================== 继续审查 ====================

    @Test
    @DisplayName("继续审查：只派发还没审成的章，已审成的一章都不重发（不重复扣费）")
    void resume_dispatchesOnlyPendingChapters() {
        when(taskMapper.selectById(TASK_ID)).thenReturn(task(AiReviewTask.STATUS_ABORTED, 3, 3, 1));
        when(chapterService.listChapterBriefs(NOVEL_ID))
                .thenReturn(List.of(chapter(1L, 1), chapter(2L, 2), chapter(3L, 3)));
        AiReviewChapter done = new AiReviewChapter();
        done.setChapterId(1L);
        when(chapterMapper.selectList(any())).thenReturn(List.of(done));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            service.resume(TASK_ID);

            // 1 章已审成 ⇒ 只剩 2、3 两章要派发（2 是上次失败的，3 是新增的）
            verify(mqSender, times(2)).sendAfterCommit(anyString(), anyString(), any());
            verify(chapterMapper).deleteFailed(TASK_ID);
        }
    }
}
