package com.ainovel.module.message.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.RoleConstant;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.message.dao.MessageMapper;
import com.ainovel.module.message.domain.entity.Message;
import com.ainovel.module.message.service.impl.MessageServiceImpl;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.ainovel.module.user.domain.entity.UserRole;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 站内信服务单测：发送、管理员群发、越权读取防护
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageMapper messageMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    private MessageService messageService;

    @BeforeEach
    void initService() {
        messageService = new MessageServiceImpl(messageMapper, userRoleMapper);
    }

    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper.getSqlSegment() 需要将 lambda 还原成列名，
        // 这依赖 MP 的 TableInfo（正常由 MyBatis 启动时注册）。纯 Mockito 单测没有 Spring/MyBatis
        // 上下文，这里手动注册一次，才能断言「生成的 SQL 中究竟带了哪些条件」。
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Message.class);
    }

    @Test
    @DisplayName("发送站内信 → 默认未读落库")
    void send_insertsUnread() {
        messageService.send(9L, "AUDIT_PASS", "标题", "内容", 100L);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        Message saved = captor.getValue();
        assertEquals(9L, saved.getUserId());
        assertEquals("AUDIT_PASS", saved.getType());
        assertEquals(100L, saved.getRelatedId());
        assertEquals(0, saved.getIsRead());
    }

    @Test
    @DisplayName("群发管理员 → 每个 admin 用户各一条")
    void sendToAdmins_sendsToEachAdmin() {
        UserRole ur1 = new UserRole();
        ur1.setUserId(1L);
        ur1.setRoleId(RoleConstant.ADMIN_ROLE_ID);
        UserRole ur2 = new UserRole();
        ur2.setUserId(2L);
        ur2.setRoleId(RoleConstant.ADMIN_ROLE_ID);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(ur1, ur2));

        messageService.sendToAdmins("AUDIT_SUBMIT", "新作品待审核", "内容", 100L);

        verify(messageMapper, times(2)).insert(any(Message.class));
    }

    @Test
    @DisplayName("标记已读 → 他人消息抛 NOT_FOUND（防越权）")
    void markRead_othersMessage_throws() {
        Message others = new Message();
        others.setId(5L);
        others.setUserId(2L);
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            when(messageMapper.selectById(5L)).thenReturn(others);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> messageService.markRead(5L));
            assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("清空已读 → 条件同时限定「当前用户」与「已读」，并返回清掉的条数")
    void clearRead_scopeByUserAndReadFlag() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(7L);
            when(messageMapper.delete(any())).thenReturn(3);

            int cleared = messageService.clearRead();

            assertEquals(3, cleared);
            ArgumentCaptor<LambdaQueryWrapper<Message>> captor =
                    ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(messageMapper).delete(captor.capture());
            // 直接断言生成的 WHERE：两个条件缺一不可
            String where = captor.getValue().getSqlSegment();
            assertTrue(where.contains("user_id"), "必须带 user_id 条件 —— 否则会删掉别人的消息，实际：" + where);
            assertTrue(where.contains("is_read"), "必须带 is_read 条件 —— 否则未读消息会被一起删掉，实际：" + where);
        }
    }

    @Test
    @DisplayName("消息分页 → 分页参数被钳位（pageNum<1 → 1，pageSize>100 → 100）")
    void page_clampsPageParams() {
        Page<Message> page = new Page<>(1, 100);
        page.setRecords(List.of());
        page.setTotal(0);
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            when(messageMapper.selectPage(any(), any())).thenReturn(page);

            messageService.page(0, 9999, null);

            ArgumentCaptor<Page<Message>> captor = ArgumentCaptor.forClass(Page.class);
            verify(messageMapper).selectPage(captor.capture(), any());
            assertEquals(1L, captor.getValue().getCurrent());
            assertEquals(100L, captor.getValue().getSize());
        }
    }
}
