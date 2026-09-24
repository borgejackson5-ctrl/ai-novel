package com.ainovel.module.feedback.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MessageTypeConstant;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.CoinTypeEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.coin.service.CoinService;
import com.ainovel.module.feedback.dao.FeedbackMapper;
import com.ainovel.module.feedback.domain.entity.Feedback;
import com.ainovel.module.feedback.domain.form.FeedbackForm;
import com.ainovel.module.feedback.domain.form.FeedbackHandleForm;
import com.ainovel.module.feedback.domain.vo.FeedbackVO;
import com.ainovel.module.feedback.service.impl.FeedbackServiceImpl;
import com.ainovel.module.message.service.MessageService;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户反馈服务单测：提交校验、处理（采纳奖励/未采纳）、幂等防重复、匿名身份脱敏
 */
@ExtendWith(MockitoExtension.class)
class FeedbackServiceTest {

    @Mock
    private FeedbackMapper feedbackMapper;
    @Mock
    private UserService userService;
    @Mock
    private CoinService coinService;
    @Mock
    private MessageService messageService;

    private FeedbackService feedbackService;

    @BeforeEach
    void initService() {
        feedbackService = new FeedbackServiceImpl(feedbackMapper, userService, coinService, messageService);
    }

    private static final Long USER_ID = 1L;

    @Test
    @DisplayName("提交反馈 → 落库（默认实名 + 待处理 + 0 奖励）")
    void submit_insertsFeedback() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            FeedbackForm form = new FeedbackForm();
            form.setType("BUG");
            form.setContent("登录后闪退");

            feedbackService.submit(form);

            ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
            verify(feedbackMapper).insert(captor.capture());
            Feedback f = captor.getValue();
            assertEquals(USER_ID, f.getUserId());
            assertEquals("BUG", f.getType());
            assertEquals(0, f.getAnonymous());
            assertEquals(0, f.getStatus());
            assertEquals(0, f.getRewardCoin());
        }
    }

    @Test
    @DisplayName("提交反馈 → 非法类型抛 PARAM_ERROR，不落库")
    void submit_invalidType_throws() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            FeedbackForm form = new FeedbackForm();
            form.setType("NOPE");
            form.setContent("x");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> feedbackService.submit(form));
            assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
            verify(feedbackMapper, never()).insert(any(Feedback.class));
        }
    }

    @Test
    @DisplayName("提交反馈 → 提醒管理员（实名带昵称与类型）")
    void submit_notifiesAdmins() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            User u = new User();
            u.setId(USER_ID);
            u.setNickname("小明");
            when(userService.getUser(USER_ID)).thenReturn(u);

            // 真实 MP 会在 insert 后回填主键；不回填时关联 id 为 null，
            // 消息点进去就没有落点。这里显式模拟，并断言消息确实带上了它。
            doAnswer(inv -> {
                Feedback saved = inv.getArgument(0);
                saved.setId(99L);
                return 1;
            }).when(feedbackMapper).insert(any(Feedback.class));

            FeedbackForm form = new FeedbackForm();
            form.setType("BUG");
            form.setContent("登录后闪退");
            feedbackService.submit(form);

            ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
            verify(messageService).sendToAdmins(eq(MessageTypeConstant.FEEDBACK_SUBMIT),
                    eq("收到新的用户反馈"), content.capture(), eq(99L));
            assertTrue(content.getValue().contains("小明"), "通知要带上反馈人，管理员才知道能找谁核实");
            assertTrue(content.getValue().contains("Bug 反馈"), "带上类型，管理员好判断优先级");
        }
    }

    @Test
    @DisplayName("提交匿名反馈 → 通知里不出现昵称，且不去查用户")
    void submit_anonymous_hidesName() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            doAnswer(inv -> {
                Feedback saved = inv.getArgument(0);
                saved.setId(99L);
                return 1;
            }).when(feedbackMapper).insert(any(Feedback.class));

            FeedbackForm form = new FeedbackForm();
            form.setType("BUG");
            form.setContent("登录后闪退");
            form.setAnonymous(1);
            feedbackService.submit(form);

            ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
            verify(messageService).sendToAdmins(eq(MessageTypeConstant.FEEDBACK_SUBMIT),
                    anyString(), content.capture(), eq(99L));
            assertTrue(content.getValue().contains("有用户"), "匿名反馈只能这样开头");
            assertFalse(content.getValue().contains("小明"), "匿名是用户明确勾的，通知里不能把身份带出来");
            verify(userService, never()).getUser(anyLong());
        }
    }

    @Test
    @DisplayName("待处理反馈数 → 供后台侧栏角标使用")
    void pendingCount_returnsCount() {
        when(feedbackMapper.selectCount(any())).thenReturn(3L);

        assertEquals(3L, feedbackService.pendingCount());
    }

    @Test
    @DisplayName("处理反馈采纳 + 奖励 → 加币 + 站内信 + 落库回复")
    void handle_adoptedWithReward() {
        Feedback pending = new Feedback();
        pending.setId(10L);
        pending.setUserId(USER_ID);
        pending.setStatus(0);
        when(feedbackMapper.selectById(10L)).thenReturn(pending);

        FeedbackHandleForm form = new FeedbackHandleForm();
        form.setStatus(1);
        form.setRewardCoin(50);
        form.setReply("感谢反馈");

        feedbackService.handle(10L, form);

        verify(coinService).award(USER_ID, 50, CoinTypeEnum.FEEDBACK_REWARD, 10L, "反馈奖励");
        // 类型已从裸字符串「feedback_reward」改为常量；奖励数额需出现在正文中
        verify(messageService).send(eq(USER_ID), eq(MessageTypeConstant.FEEDBACK_RESULT),
                eq("反馈已被采纳"), contains("50"), eq(10L));
        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals(50, captor.getValue().getRewardCoin());
        assertEquals("感谢反馈", captor.getValue().getReply());
    }

    @Test
    @DisplayName("处理反馈未采纳 → 不奖励，但**必须**通知用户并带上管理员回复")
    void handle_rejected_notifiesUser() {
        Feedback pending = new Feedback();
        pending.setId(10L);
        pending.setUserId(USER_ID);
        pending.setStatus(0);
        when(feedbackMapper.selectById(10L)).thenReturn(pending);

        FeedbackHandleForm form = new FeedbackHandleForm();
        form.setStatus(2);
        form.setReply("暂不采纳");

        feedbackService.handle(10L, form);

        verify(coinService, never()).award(anyLong(), anyInt(), any(), anyLong(), anyString());
        // 若只断言「不通知」，用户提交反馈后不会收到任何消息，连管理员填写的拒绝理由也看不到。
        // 因此三种结果都要发送，未采纳时还需带上回复内容。
        verify(messageService).send(eq(USER_ID), eq(MessageTypeConstant.FEEDBACK_RESULT),
                eq("反馈处理结果"), contains("暂不采纳"), eq(10L));
        ArgumentCaptor<Feedback> captor = ArgumentCaptor.forClass(Feedback.class);
        verify(feedbackMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getRewardCoin());
    }

    @Test
    @DisplayName("处理已处理反馈 → 抛 FEEDBACK_ALREADY_HANDLED，不重复奖励")
    void handle_alreadyHandled_throws() {
        Feedback done = new Feedback();
        done.setId(10L);
        done.setUserId(USER_ID);
        done.setStatus(1);
        when(feedbackMapper.selectById(10L)).thenReturn(done);

        FeedbackHandleForm form = new FeedbackHandleForm();
        form.setStatus(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> feedbackService.handle(10L, form));
        assertEquals(ErrorCode.FEEDBACK_ALREADY_HANDLED, ex.getErrorCode());
        verify(feedbackMapper, never()).updateById(any(Feedback.class));
        verify(coinService, never()).award(anyLong(), anyInt(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("管理员分页 → 匿名反馈不回填身份（不查用户）")
    void adminPage_anonymous_masksIdentity() {
        Feedback f = new Feedback();
        f.setId(1L);
        f.setUserId(USER_ID);
        f.setAnonymous(1);
        Page<Feedback> page = new Page<>(1, 10);
        page.setRecords(List.of(f));
        page.setTotal(1);
        when(feedbackMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<FeedbackVO> result = feedbackService.adminPage(1, 10, null, null);

        assertEquals(1, result.getList().size());
        assertNull(result.getList().get(0).getUsername());
        verify(userService, never()).listUsers(any());
    }
}
