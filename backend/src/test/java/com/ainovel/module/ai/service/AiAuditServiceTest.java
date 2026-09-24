package com.ainovel.module.ai.service;

import com.ainovel.common.audit.SensitiveWordFilter;
import com.ainovel.common.constant.MessageTypeConstant;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.module.ai.client.AiChatClient;
import com.ainovel.module.ai.service.impl.AiAuditServiceImpl;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.message.service.MessageService;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI 预审单测：违规直接拒绝并通知作者；合规转人工终审并提醒管理员（作品级 + 章节级）
 */
@ExtendWith(MockitoExtension.class)
class AiAuditServiceTest {

    @Mock
    private NovelMapper novelMapper;

    @Mock
    private ChapterMapper chapterMapper;

    @Mock
    private ChapterService chapterService;

    @Mock
    private AiChatClient aiClient;

    @Mock
    private AiConfigService aiConfigService;

    @Mock
    private MessageService messageService;

    @Mock
    private NovelService novelService;

    @Mock
    private SensitiveWordFilter sensitiveWordFilter;

    private AiAuditService aiAuditService;

    @BeforeEach
    void initService() {
        aiAuditService = new AiAuditServiceImpl(novelMapper, chapterMapper, chapterService, aiClient, aiConfigService, messageService, novelService, sensitiveWordFilter);
    }

    private Novel novelOf(String title, String intro, Long userId) {
        Novel novel = new Novel();
        novel.setId(100L);
        novel.setTitle(title);
        novel.setIntro(intro);
        novel.setUserId(userId);
        novel.setAuthor("作者");
        return novel;
    }

    private Chapter chapterOf(String title, String content, int status) {
        Chapter chapter = new Chapter();
        chapter.setId(200L);
        chapter.setNovelId(100L);
        chapter.setChapterNo(2);
        chapter.setTitle(title);
        chapter.setContent(content);
        chapter.setAuditStatus(status);
        return chapter;
    }

    @Test
    @DisplayName("命中敏感词 → 置拒绝 + 站内信通知作者（含原因）")
    void audit_sensitiveWord_rejectsAndNotifiesAuthor() {
        when(novelService.getNovel(100L)).thenReturn(novelOf("涉赌博题材", null, 9L));
        // 敏感词过滤已抽为公共组件（词表在配置中），此处只关注「命中即拒绝」这条分支
        when(sensitiveWordFilter.match(anyString())).thenReturn("赌博");

        boolean pass = aiAuditService.audit(100L);

        assertFalse(pass);
        ArgumentCaptor<Novel> captor = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getAuditStatus());
        assertTrue(captor.getValue().getAuditResult().contains("赌博"));

        verify(messageService).send(eq(9L), eq(MessageTypeConstant.AUDIT_REJECT),
                anyString(), contains("赌博"), eq(100L));
        // 不应再走 AI 二次审核
        verifyNoInteractions(aiClient);
    }

    @Test
    @DisplayName("预审通过 → 保持待审核 + 提醒管理员人工终审")
    void audit_pass_keepsWaitAndNotifiesAdmins() {
        when(novelService.getNovel(100L)).thenReturn(novelOf("正常的书", "正常简介", 9L));
        com.ainovel.module.ai.domain.entity.AiConfig config =
                new com.ainovel.module.ai.domain.entity.AiConfig();
        when(aiConfigService.getActiveConfig()).thenReturn(config);

        boolean pass = aiAuditService.audit(100L);

        assertTrue(pass);
        ArgumentCaptor<Novel> captor = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).updateById(captor.capture());
        // 只更新审核意见，不改审核状态（保持 0 待人工终审）
        assertNull(captor.getValue().getAuditStatus());

        verify(messageService).sendToAdmins(eq(MessageTypeConstant.AUDIT_SUBMIT),
                anyString(), contains("正常的书"), eq(100L));
        verify(messageService, never()).send(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("章节命中敏感词 → 置拒绝 + 通知作者")
    void auditChapter_sensitiveWord_rejectsAndNotifiesAuthor() {
        when(chapterService.getById(200L)).thenReturn(chapterOf("涉赌博", "正文", ChapterAuditStatusEnum.WAIT.getCode()));
        when(novelService.getNovel(100L)).thenReturn(novelOf("书名", null, 9L));
        when(sensitiveWordFilter.match(anyString())).thenReturn("赌博");

        boolean pass = aiAuditService.auditChapter(200L);

        assertFalse(pass);
        ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).updateById(captor.capture());
        assertEquals(ChapterAuditStatusEnum.REJECT.getCode(), captor.getValue().getAuditStatus());
        assertTrue(captor.getValue().getAuditResult().contains("赌博"));

        verify(messageService).send(eq(9L), eq(MessageTypeConstant.AUDIT_REJECT),
                anyString(), contains("赌博"), eq(200L));
        verifyNoInteractions(aiClient);
    }

    @Test
    @DisplayName("章节预审通过 → 保持待审 + 提醒管理员人工终审")
    void auditChapter_pass_keepsWaitAndNotifiesAdmins() {
        when(chapterService.getById(200L)).thenReturn(chapterOf("正常章节", "正常正文", ChapterAuditStatusEnum.WAIT.getCode()));
        when(novelService.getNovel(100L)).thenReturn(novelOf("书名", null, 9L));
        com.ainovel.module.ai.domain.entity.AiConfig config =
                new com.ainovel.module.ai.domain.entity.AiConfig();
        when(aiConfigService.getActiveConfig()).thenReturn(config);

        boolean pass = aiAuditService.auditChapter(200L);

        assertTrue(pass);
        // 预审通过不改章节审核状态，直接转人工终审
        verify(chapterMapper, never()).updateById(any(Chapter.class));
        verify(messageService).sendToAdmins(eq(MessageTypeConstant.AUDIT_SUBMIT),
                anyString(), contains("第2章"), eq(200L));
        verify(messageService, never()).send(anyLong(), anyString(), anyString(), anyString(), anyLong());
    }
}
