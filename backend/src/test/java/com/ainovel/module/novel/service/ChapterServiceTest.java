package com.ainovel.module.novel.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.message.ChapterChunkSyncMessage;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.ChapterAuditStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.mq.MqSender;
import com.ainovel.common.util.CacheHelper;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.form.ChapterSaveForm;
import com.ainovel.module.novel.domain.vo.ChapterContentVO;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.ainovel.module.novel.service.impl.ChapterServiceImpl;
import com.ainovel.module.novel.spi.ChapterAccessChecker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 章节服务单测：章节目录缓存接入 + 失效 + 章节增删改（连载/影子正文/权限/重算）
 */
@ExtendWith(MockitoExtension.class)
class ChapterServiceTest {

    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private NovelMapper novelMapper;
    @Mock
    private CacheHelper cacheHelper;
    @Mock
    private ChapterAccessChecker chapterAccessChecker;
    @Mock
    private MqSender mqSender;
    @Mock
    private NovelService novelService;

    private ChapterService chapterService;

    @BeforeEach
    void setUp() throws Exception {
        chapterService = new ChapterServiceImpl(chapterAccessChecker, cacheHelper, novelMapper, mqSender, novelService);
        // ServiceImpl 的 baseMapper（声明在 CrudRepository 父类）由 Spring @Autowired 注入，纯 Mockito 测试需手动反射塞入
        java.lang.reflect.Field baseMapper = CrudRepository.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(chapterService, chapterMapper);

        lenient().when(cacheHelper.get(anyString(), any(), any(), anyLong(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());
        // 章节写操作后会重算作品聚合（总章数/总字数/打包价），默认给一份空统计
        lenient().when(chapterMapper.selectVisibleStats(anyLong()))
                .thenReturn(Map.of("chapterCount", 0L, "wordCount", 0L, "paidCoinSum", 0L));
    }

    private Novel ownerNovel(Long novelId, Long userId) {
        Novel novel = new Novel();
        novel.setId(novelId);
        novel.setUserId(userId);
        novel.setTitle("测试书");
        return novel;
    }

    @Test
    @DisplayName("分页目录 → 缓存 key 含页/条数 + 回源分页映射")
    void pageVOByNovel_maps() {
        Chapter c1 = new Chapter();
        c1.setId(200L);
        c1.setChapterNo(1);
        c1.setTitle("第一回");
        c1.setWordCount(100);
        c1.setUnlockCoin(0);
        Page<Chapter> page = new Page<>(2, 50);
        page.setRecords(List.of(c1));
        page.setTotal(101);
        when(chapterMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<ChapterVO> result = chapterService.pageVOByNovel(100L, 2, 50);

        assertEquals(101L, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals(200L, result.getList().get(0).getId());
        verify(cacheHelper).get(eq("novel:chapter:page:100:2:50"), any(), any(), anyLong(), any());
        verify(chapterMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("单章元数据 → 命中映射")
    void getChapterVO_maps() {
        Chapter c = new Chapter();
        c.setId(200L);
        c.setChapterNo(3);
        c.setTitle("第三回");
        c.setWordCount(300);
        c.setUnlockCoin(5);
        when(chapterMapper.selectById(200L)).thenReturn(c);

        ChapterVO vo = chapterService.getChapterVO(200L);

        assertEquals("第三回", vo.getTitle());
        assertEquals(5, vo.getUnlockCoin());
    }

    @Test
    @DisplayName("单章元数据 → 章节不存在抛 NOT_FOUND")
    void getChapterVO_notFound_throws() {
        when(chapterMapper.selectById(200L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> chapterService.getChapterVO(200L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("正文 → 免费章填充上/下一章 ID（不查订单），可见章不查 novel")
    void getContent_fillsNeighbors() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            Chapter cur = new Chapter();
            cur.setId(200L);
            cur.setNovelId(100L);
            cur.setChapterNo(3);
            cur.setTitle("第三回");
            cur.setContent("正文");
            cur.setWordCount(300);
            cur.setUnlockCoin(0); // 免费章，跳过解锁校验
            cur.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode()); // 已通过，跳过 novel 可见性校验
            // 回源取正文走 getById → selectById
            when(chapterMapper.selectById(200L)).thenReturn(cur);

            Chapter prev = new Chapter();
            prev.setId(199L);
            Chapter next = new Chapter();
            next.setId(201L);
            // getContent 先用元数据投影（selectOne）判权限；fillNeighbors 前后各查一次。
            // 三处都走 baseMapper.selectOne(wrapper, true) 两参重载，按调用顺序打桩。
            when(chapterMapper.selectOne(any(), anyBoolean())).thenReturn(cur).thenReturn(prev).thenReturn(next);

            ChapterContentVO vo = chapterService.getContent(200L);

            assertEquals("正文", vo.getContent());
            assertEquals(199L, vo.getPrevChapterId());
            assertEquals(201L, vo.getNextChapterId());
            verify(novelMapper, never()).selectById(anyLong());
        }
    }

    @Test
    @DisplayName("正文 → 待审章且非作者/管理员拒绝访问")
    void getContent_pending_throws() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            Chapter cur = new Chapter();
            cur.setId(200L);
            cur.setNovelId(100L);
            cur.setChapterNo(3);
            cur.setContent("正文");
            cur.setAuditStatus(ChapterAuditStatusEnum.WAIT.getCode());
            // 权限判断走元数据投影查询（selectOne 两参重载）
            when(chapterMapper.selectOne(any(), anyBoolean())).thenReturn(cur);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 9L)); // 作者是 9，非当前用户 1

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> chapterService.getContent(200L));
            assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("正文 → 游客读免费章放行（不注册也能先看一章）")
    void getContent_freeChapter_guestAllowed() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            // 必须显式 stub 成 null：Mockito 对包装类型的未 stub 方法返回的是
            // Primitives.defaultValue（Long → 0L），而不是 null。不写这行时「游客」
            // 会变成「id=0 的登录用户」，测到的就不是该路径
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(null);
            Chapter free = new Chapter();
            free.setId(200L);
            free.setNovelId(100L);
            free.setChapterNo(3);
            free.setTitle("第三回");
            free.setContent("正文");
            free.setUnlockCoin(0);
            free.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
            when(chapterMapper.selectById(200L)).thenReturn(free);
            // 第一次是权限判定的元数据投影；后两次是 fillNeighbors 找上下章
            when(chapterMapper.selectOne(any(), anyBoolean()))
                    .thenReturn(free).thenReturn(null).thenReturn(null);

            ChapterContentVO vo = chapterService.getContent(200L);

            assertEquals("正文", vo.getContent());
            // 免费章不应查询解锁状态：游客没有任何订单可查
            verify(chapterAccessChecker, never()).canRead(any(), anyLong(), anyLong());
        }
    }

    @Test
    @DisplayName("正文 → 游客读付费章要求登录（401 会让前端跳登录页）")
    void getContent_paidChapter_guestNeedsLogin() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            // 同上：显式 stub 成 null 才是「没登录」
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(null);
            Chapter paid = new Chapter();
            paid.setId(200L);
            paid.setNovelId(100L);
            paid.setChapterNo(3);
            paid.setUnlockCoin(5);
            paid.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
            when(chapterMapper.selectOne(any(), anyBoolean())).thenReturn(paid);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> chapterService.getContent(200L));

            assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
            // 游客谈不上「已解锁」，连查都不用查
            verify(chapterAccessChecker, never()).canRead(any(), anyLong(), anyLong());
        }
    }

    @Test
    @DisplayName("失效章节目录缓存 → 按模式清分页 key，且排在事务提交之后（无事务则立即）")
    void evictListCache_deletesKey() {
        chapterService.evictListCache(100L);

        // 用 afterCommit 版本：事务内直接删会让并发读把旧目录回填进缓存。
        // 单测里没有事务，CacheHelper 会走「立即执行」分支，行为等价。
        verify(cacheHelper).evictByPatternAfterCommit("novel:chapter:page:100:*");
    }

    // ==================== 章节增删改 ====================

    @Test
    @DisplayName("作者视角目录分页 → 返回全部章节（含审核状态）并走分页查询")
    void pageAuthorVOByNovel_maps() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Chapter c = new Chapter();
            c.setId(200L);
            c.setChapterNo(1);
            c.setTitle("第一章");
            c.setAuditStatus(ChapterAuditStatusEnum.WAIT.getCode());
            Page<Chapter> page = new Page<>(1, 20);
            page.setRecords(List.of(c));
            page.setTotal(232);
            when(chapterMapper.selectPage(any(), any())).thenReturn(page);

            PageResult<ChapterVO> result = chapterService.pageAuthorVOByNovel(100L, 1, 20);

            assertEquals(232L, result.getTotal());
            assertEquals(1, result.getList().size());
            assertEquals(ChapterAuditStatusEnum.WAIT.getCode(), result.getList().get(0).getAuditStatus());
            verify(chapterMapper).selectPage(any(), any());
        }
    }

    @Test
    @DisplayName("作者视角目录分页 → 页码/页大小越界时被钳位（pageNum<1 → 1，pageSize>100 → 100）")
    void pageAuthorVOByNovel_clamps() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Page<Chapter> page = new Page<>(1, 100);
            page.setRecords(List.of());
            page.setTotal(0);
            when(chapterMapper.selectPage(any(), any())).thenReturn(page);

            chapterService.pageAuthorVOByNovel(100L, 0, 9999);

            ArgumentCaptor<Page<Chapter>> captor = ArgumentCaptor.forClass(Page.class);
            verify(chapterMapper).selectPage(captor.capture(), any());
            assertEquals(1L, captor.getValue().getCurrent());
            assertEquals(100L, captor.getValue().getSize());
        }
    }

    @Test
    @DisplayName("作者视角目录分页 → 非作者/管理员拒绝")
    void pageAuthorVOByNovel_notOwner_throws() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 9L));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> chapterService.pageAuthorVOByNovel(100L, 1, 20));
            assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("新增章节 → 章序号自增 + 待审 + 发 AI 预审")
    void addChapter_appends() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            when(chapterMapper.selectMaxChapterNo(100L)).thenReturn(5);

            ChapterSaveForm form = new ChapterSaveForm();
            form.setTitle("第六章");
            form.setContent("新内容");
            form.setUnlockCoin(5);

            ChapterVO vo = chapterService.addChapter(100L, form);

            assertEquals(6, vo.getChapterNo());
            assertEquals(ChapterAuditStatusEnum.WAIT.getCode(), vo.getAuditStatus());
            ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
            verify(chapterMapper).insert(captor.capture());
            assertEquals(6, captor.getValue().getChapterNo());
            assertEquals(ChapterAuditStatusEnum.WAIT.getCode(), captor.getValue().getAuditStatus());
            verify(mqSender).sendAfterCommit(eq(MqConstant.AI_EXCHANGE), eq(MqConstant.AI_AUDIT_ROUTING_KEY), any());
        }
    }

    @Test
    @DisplayName("修改已发布章 → 影子正文（pending_content + 变更待审）")
    void updateChapter_published_usesShadow() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Chapter chapter = new Chapter();
            chapter.setId(200L);
            chapter.setNovelId(100L);
            chapter.setChapterNo(2);
            chapter.setTitle("旧标题");
            chapter.setContent("旧正文");
            chapter.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
            when(chapterMapper.selectById(200L)).thenReturn(chapter);

            ChapterSaveForm form = new ChapterSaveForm();
            form.setTitle("新标题");
            form.setContent("新正文");
            form.setUnlockCoin(5);

            chapterService.updateChapter(200L, form);

            ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
            verify(chapterMapper).updateById(captor.capture());
            assertEquals(ChapterAuditStatusEnum.MODIFY_WAIT.getCode(), captor.getValue().getAuditStatus());
            assertEquals("新正文", captor.getValue().getPendingContent());
            assertNull(captor.getValue().getContent());
        }
    }

    @Test
    @DisplayName("改章节 → 投一条章节块重建消息（块索引的写入路径，别再退化成只有手动重建）")
    void updateChapter_requestsChunkReindex() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Chapter chapter = new Chapter();
            chapter.setId(200L);
            chapter.setNovelId(100L);
            chapter.setChapterNo(2);
            chapter.setContent("旧正文");
            chapter.setAuditStatus(ChapterAuditStatusEnum.WAIT.getCode());
            when(chapterMapper.selectById(200L)).thenReturn(chapter);

            ChapterSaveForm form = new ChapterSaveForm();
            form.setTitle("同一标题");
            form.setContent("改过的正文");
            chapterService.updateChapter(200L, form);

            // 缺少这条断言时，删除 afterChapterChange 中的那行调用不会有任何测试失败，
            // 而症状是「索引中没有新正文、检索不到、不报错」，正是需要约束的静默缺陷
            ArgumentCaptor<ChapterChunkSyncMessage> chunkCaptor =
                    ArgumentCaptor.forClass(ChapterChunkSyncMessage.class);
            verify(mqSender).sendAfterCommit(eq(MqConstant.SEARCH_EXCHANGE),
                    eq(MqConstant.SEARCH_CHUNK_ROUTING_KEY), chunkCaptor.capture());
            assertEquals(100L, chunkCaptor.getValue().getNovelId());
            assertEquals(200L, chunkCaptor.getValue().getChapterId());
        }
    }

    @Test
    @DisplayName("修改待审章 → 直接覆盖正文，重新待审")
    void updateChapter_wait_directOverwrite() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Chapter chapter = new Chapter();
            chapter.setId(200L);
            chapter.setNovelId(100L);
            chapter.setChapterNo(2);
            chapter.setContent("旧正文");
            chapter.setAuditStatus(ChapterAuditStatusEnum.WAIT.getCode());
            when(chapterMapper.selectById(200L)).thenReturn(chapter);

            ChapterSaveForm form = new ChapterSaveForm();
            form.setContent("新正文");

            chapterService.updateChapter(200L, form);

            ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
            verify(chapterMapper).updateById(captor.capture());
            assertEquals(ChapterAuditStatusEnum.WAIT.getCode(), captor.getValue().getAuditStatus());
            assertEquals("新正文", captor.getValue().getContent());
            assertNull(captor.getValue().getPendingContent());
        }
    }

    @Test
    @DisplayName("修改首章 → 强制免费（unlockCoin=0）")
    void updateChapter_firstChapter_forceFree() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Chapter chapter = new Chapter();
            chapter.setId(200L);
            chapter.setNovelId(100L);
            chapter.setChapterNo(1);
            chapter.setContent("旧正文");
            chapter.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
            when(chapterMapper.selectById(200L)).thenReturn(chapter);

            ChapterSaveForm form = new ChapterSaveForm();
            form.setContent("新正文");
            form.setUnlockCoin(10);

            chapterService.updateChapter(200L, form);

            ArgumentCaptor<Chapter> captor = ArgumentCaptor.forClass(Chapter.class);
            verify(chapterMapper).updateById(captor.capture());
            assertEquals(0, captor.getValue().getUnlockCoin());
        }
    }

    @Test
    @DisplayName("删除已发布章 → 拒绝")
    void deleteChapter_published_throws() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Chapter chapter = new Chapter();
            chapter.setId(200L);
            chapter.setNovelId(100L);
            chapter.setAuditStatus(ChapterAuditStatusEnum.PASS.getCode());
            when(chapterMapper.selectById(200L)).thenReturn(chapter);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> chapterService.deleteChapter(200L));
            assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
            verify(chapterMapper, never()).deleteById(anyLong());
        }
    }

    @Test
    @DisplayName("删除待审章 → 逻辑删除 + 重算聚合")
    void deleteChapter_wait_removes() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Chapter chapter = new Chapter();
            chapter.setId(200L);
            chapter.setNovelId(100L);
            chapter.setAuditStatus(ChapterAuditStatusEnum.WAIT.getCode());
            when(chapterMapper.selectById(200L)).thenReturn(chapter);
            when(chapterMapper.selectVisibleStats(100L))
                    .thenReturn(Map.of("chapterCount", 0L, "wordCount", 0L, "paidCoinSum", 0L));

            chapterService.deleteChapter(200L);

            verify(chapterMapper).deleteById(200L);
        }
    }

    @Test
    @DisplayName("重算聚合 → 总章数/字数/整本打包价（付费章之和×六折）")
    void recountNovel_computes() {
        when(chapterMapper.selectVisibleStats(100L)).thenReturn(Map.of(
                "chapterCount", 10L, "wordCount", 1000L, "paidCoinSum", 100L));

        chapterService.recountNovel(100L);

        ArgumentCaptor<Novel> captor = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).updateById(captor.capture());
        assertEquals(10, captor.getValue().getTotalChapters());
        assertEquals(1000L, captor.getValue().getWordCount());
        assertEquals(60, captor.getValue().getCoinPrice());
    }

    @Test
    @DisplayName("删除变更待审章 → 拒绝（读者仍可见旧版，不可删）")
    void deleteChapter_modifyWait_throws() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            Chapter chapter = new Chapter();
            chapter.setId(200L);
            chapter.setNovelId(100L);
            chapter.setAuditStatus(ChapterAuditStatusEnum.MODIFY_WAIT.getCode());
            when(chapterMapper.selectById(200L)).thenReturn(chapter);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> chapterService.deleteChapter(200L));
            assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
            verify(chapterMapper, never()).deleteById(anyLong());
        }
    }

    @Test
    @DisplayName("新增首章（章节清空后 nextNo=1）→ 强制免费 unlockCoin=0")
    void addChapter_firstChapter_forceFree() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            when(chapterMapper.selectMaxChapterNo(100L)).thenReturn(0); // 无章节，nextNo=1

            ChapterSaveForm form = new ChapterSaveForm();
            form.setTitle("第一章");
            form.setContent("新内容");
            form.setUnlockCoin(10);

            ChapterVO vo = chapterService.addChapter(100L, form);

            assertEquals(1, vo.getChapterNo());
            assertEquals(0, vo.getUnlockCoin());
        }
    }

    @Test
    @DisplayName("新增章节撞唯一索引 → 重读 max 乐观重试")
    void addChapter_retriesOnDuplicateKey() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(1L);
            mocked.when(LoginUserUtil::isAdmin).thenReturn(false);
            when(novelMapper.selectById(100L)).thenReturn(ownerNovel(100L, 1L));
            when(chapterMapper.selectMaxChapterNo(100L)).thenReturn(5, 6);
            doThrow(new DataIntegrityViolationException("duplicate chapter_no"))
                    .doAnswer(inv -> 1)
                    .when(chapterMapper).insert(any(Chapter.class));

            ChapterSaveForm form = new ChapterSaveForm();
            form.setTitle("第六章");
            form.setContent("新内容");
            form.setUnlockCoin(5);

            ChapterVO vo = chapterService.addChapter(100L, form);

            assertEquals(7, vo.getChapterNo());
            verify(chapterMapper, times(2)).insert(any(Chapter.class));
        }
    }
}
