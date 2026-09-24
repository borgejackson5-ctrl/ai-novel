package com.ainovel.module.admin.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MessageTypeConstant;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.AuditStatusEnum;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.admin.domain.vo.NovelAuditVO;
import com.ainovel.module.admin.service.impl.AdminServiceImpl;
import com.ainovel.module.category.dao.CategoryMapper;
import com.ainovel.module.coin.dao.RechargeOrderMapper;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.vo.ChapterAuditVO;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.message.service.MessageService;
import com.ainovel.common.message.SearchSyncMessage;
import com.ainovel.module.subscribe.dao.SubscribeOrderMapper;
import com.ainovel.module.user.dao.RoleMapper;
import com.ainovel.module.user.dao.UserMapper;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ainovel.common.mq.MqSender;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 管理后台审核单测：人工终审（作品级）通过/拒绝 + 章节级审核通过/拒绝
 */
@ExtendWith(MockitoExtension.class)
class AdminAuditServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private NovelMapper novelMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private SubscribeOrderMapper subscribeOrderMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private MqSender mqSender;
    @Mock
    private NovelService novelService;
    @Mock
    private ChapterService chapterService;

    private AdminService adminService;

    @BeforeEach
    void initService() {
        adminService = new AdminServiceImpl(userMapper, novelMapper, chapterMapper, categoryMapper, rechargeOrderMapper, subscribeOrderMapper, userRoleMapper, roleMapper, messageService, mqSender, novelService, chapterService);
    }

    private Novel waitNovel() {
        Novel novel = new Novel();
        novel.setId(100L);
        novel.setTitle("待审作品");
        novel.setCategoryId(1L);
        novel.setUserId(9L);
        novel.setAuditStatus(AuditStatusEnum.WAIT.getCode());
        return novel;
    }

    private Chapter chapterOf(int status, String content) {
        Chapter chapter = new Chapter();
        chapter.setId(200L);
        chapter.setNovelId(100L);
        chapter.setChapterNo(2);
        chapter.setTitle("第二章");
        chapter.setContent(content);
        chapter.setAuditStatus(status);
        return chapter;
    }

    @Test
    @DisplayName("作品审核分页 → 首章节选走一次批量查询（原先逐本查是 N+1），且超长截断")
    void auditPage_fillsExcerptWithSingleBatchQuery() {
        Page<Novel> page = new Page<>(1, 10);
        page.setRecords(List.of(waitNovel()));
        page.setTotal(1);
        when(novelMapper.selectPage(any(), any())).thenReturn(page);
        when(categoryMapper.selectBatchIds(anyList())).thenReturn(List.of());

        String longContent = "正".repeat(600);
        Chapter first = chapterOf(ChapterAuditStatusEnum.PASS.getCode(), longContent);
        when(chapterMapper.selectFirstChaptersByNovelIds(anyList())).thenReturn(List.of(first));

        PageResult<NovelAuditVO> result = adminService.auditPage(1, 10, null);

        assertEquals(1, result.getList().size());
        String excerpt = result.getList().get(0).getExcerpt();
        assertNotNull(excerpt, "首章节选没回填");
        assertEquals(501, excerpt.length(), "超过 500 字应截断并补一个省略号");
        assertTrue(excerpt.endsWith("…"));

        // 一条批量查询搞定，而不是每本一次 selectOne
        verify(chapterMapper, times(1)).selectFirstChaptersByNovelIds(anyList());
        verify(chapterMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("审核通过 → 状态通过 + 自动上架 + 章节批量置通过 + 同步 ES + 通知作者")
    void auditPass_marksPassAndOnline() {
        when(novelMapper.selectById(100L)).thenReturn(waitNovel());

        adminService.auditPass(100L, null);

        ArgumentCaptor<Novel> captor = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).updateById(captor.capture());
        assertEquals(AuditStatusEnum.PASS.getCode(), captor.getValue().getAuditStatus());
        assertEquals(1, captor.getValue().getStatus());

        // 作品过审 → 待审章节批量置通过
        ArgumentCaptor<Chapter> chapterCaptor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).update(chapterCaptor.capture(), any());
        assertEquals(ChapterAuditStatusEnum.PASS.getCode(), chapterCaptor.getValue().getAuditStatus());

        verify(chapterService).evictListCache(100L);

        verify(mqSender).sendAfterCommit(
                eq(MqConstant.SEARCH_EXCHANGE),
                eq(MqConstant.SEARCH_SYNC_ROUTING_KEY),
                argThat((SearchSyncMessage m) -> "UPSERT".equals(m.getOperation())));

        verify(messageService).send(eq(9L), eq(MessageTypeConstant.AUDIT_PASS),
                anyString(), contains("待审作品"), eq(100L));
    }

    @Test
    @DisplayName("审核拒绝 → 状态拒绝 + 保持下架 + 通知作者（含理由）")
    void auditReject_marksRejectWithReason() {
        when(novelMapper.selectById(100L)).thenReturn(waitNovel());

        adminService.auditReject(100L, "内容质量不达标");

        ArgumentCaptor<Novel> captor = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).updateById(captor.capture());
        assertEquals(AuditStatusEnum.REJECT.getCode(), captor.getValue().getAuditStatus());
        assertEquals(0, captor.getValue().getStatus());
        assertEquals("内容质量不达标", captor.getValue().getAuditResult());

        verify(messageService).send(eq(9L), eq(MessageTypeConstant.AUDIT_REJECT),
                anyString(), contains("内容质量不达标"), eq(100L));
        // 拒绝的作品未上架，不应同步 ES
        verify(mqSender, never()).sendAfterCommit(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("非待审核状态 → 拒绝操作（防止重复终审）")
    void auditPass_notWait_throws() {
        Novel passed = waitNovel();
        passed.setAuditStatus(AuditStatusEnum.PASS.getCode());
        when(novelMapper.selectById(100L)).thenReturn(passed);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.auditPass(100L, null));
        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
    }

    // ==================== 章节级审核 ====================

    @Test
    @DisplayName("章节审核分页 → 默认查待审+变更待审，映射书名/作者/节选")
    void auditChapterPage_maps() {
        Chapter c = chapterOf(ChapterAuditStatusEnum.WAIT.getCode(), "正文节选");
        Page<Chapter> page = new Page<>(1, 10);
        page.setRecords(List.of(c));
        page.setTotal(1);
        when(chapterMapper.selectPage(any(), any())).thenReturn(page);

        Novel novel = waitNovel();
        novel.setAuthor("某作者");
        when(novelMapper.selectBatchIds(anyList())).thenReturn(List.of(novel));

        PageResult<ChapterAuditVO> result = adminService.auditChapterPage(1, 10, null);

        assertEquals(1, result.getList().size());
        ChapterAuditVO vo = result.getList().get(0);
        assertEquals(200L, vo.getId());
        assertEquals("待审作品", vo.getNovelTitle());
        assertEquals("某作者", vo.getAuthor());
        assertEquals("正文节选", vo.getExcerpt());
    }

    @Test
    @DisplayName("章节审核通过（新增章）→ 0→1 + 重算聚合 + 失效缓存 + 通知作者")
    void auditChapterPass_newChapter_passes() {
        when(chapterMapper.selectById(200L)).thenReturn(chapterOf(ChapterAuditStatusEnum.WAIT.getCode(), "正文"));
        Novel novel = waitNovel();
        when(novelMapper.selectById(100L)).thenReturn(novel);

        adminService.auditChapterPass(200L);

        ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).updateById(captor.capture());
        assertEquals(ChapterAuditStatusEnum.PASS.getCode(), captor.getValue().getAuditStatus());

        verify(chapterService).recountNovel(100L);
        verify(chapterService).evictListCache(100L);
        verify(novelService).evictDetailCache(100L);
        verify(messageService).send(eq(9L), eq(MessageTypeConstant.AUDIT_PASS),
                anyString(), contains("第2章"), eq(200L));
    }

    @Test
    @DisplayName("章节审核通过（变更待审）→ 3→1 影子正文原子替换")
    void auditChapterPass_modifyWait_replacesContent() {
        Chapter c = chapterOf(ChapterAuditStatusEnum.MODIFY_WAIT.getCode(), "旧版正文");
        c.setPendingContent("新版正文");
        when(chapterMapper.selectById(200L)).thenReturn(c);
        Novel novel = waitNovel();
        when(novelMapper.selectById(100L)).thenReturn(novel);

        adminService.auditChapterPass(200L);

        ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).updateById(captor.capture());
        assertEquals(ChapterAuditStatusEnum.PASS.getCode(), captor.getValue().getAuditStatus());
        assertEquals("新版正文", captor.getValue().getContent());
        assertNull(captor.getValue().getPendingContent());
    }

    @Test
    @DisplayName("章节审核拒绝（新增章）→ 0→2 + 通知作者（含理由）")
    void auditChapterReject_newChapter_rejects() {
        when(chapterMapper.selectById(200L)).thenReturn(chapterOf(ChapterAuditStatusEnum.WAIT.getCode(), "正文"));
        Novel novel = waitNovel();
        when(novelMapper.selectById(100L)).thenReturn(novel);

        adminService.auditChapterReject(200L, "涉敏");

        ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).updateById(captor.capture());
        assertEquals(ChapterAuditStatusEnum.REJECT.getCode(), captor.getValue().getAuditStatus());
        assertEquals("涉敏", captor.getValue().getAuditResult());
        // 拒绝不影响读者可见集合，不重算聚合
        verify(chapterService, never()).recountNovel(anyLong());
    }

    @Test
    @DisplayName("章节审核拒绝（变更待审）→ 3→1 回退旧版（清影子正文）")
    void auditChapterReject_modifyWait_rollsBack() {
        Chapter c = chapterOf(ChapterAuditStatusEnum.MODIFY_WAIT.getCode(), "旧版正文");
        c.setPendingContent("新版正文");
        when(chapterMapper.selectById(200L)).thenReturn(c);
        Novel novel = waitNovel();
        when(novelMapper.selectById(100L)).thenReturn(novel);

        adminService.auditChapterReject(200L, "涉敏");

        ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).updateById(captor.capture());
        assertEquals(ChapterAuditStatusEnum.PASS.getCode(), captor.getValue().getAuditStatus());
        assertNull(captor.getValue().getPendingContent());
    }

    @Test
    @DisplayName("非待审章节 → 拒绝审核操作")
    void auditChapterPass_notWait_throws() {
        when(chapterMapper.selectById(200L)).thenReturn(chapterOf(ChapterAuditStatusEnum.PASS.getCode(), "正文"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.auditChapterPass(200L));
        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
    }
}
