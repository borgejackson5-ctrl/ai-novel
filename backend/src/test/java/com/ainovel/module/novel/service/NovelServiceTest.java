package com.ainovel.module.novel.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.CacheHelper;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.common.message.AiAuditMessage;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.dao.ChapterMapper;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.form.ChapterForm;
import com.ainovel.module.novel.domain.form.NovelForm;
import com.ainovel.module.novel.domain.form.NovelPublishForm;
import com.ainovel.module.novel.domain.vo.LikeVO;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.novel.service.impl.NovelServiceImpl;
import com.ainovel.common.message.NovelReadMessage;
import com.ainovel.common.message.OssDeleteMessage;
import com.ainovel.common.message.SearchSyncMessage;
import com.ainovel.module.message.service.MessageService;
import com.ainovel.module.novel.dao.NovelAppealMapper;
import com.ainovel.module.novel.spi.NovelPurchaseProbe;
import com.ainovel.module.user.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ainovel.common.mq.MqSender;
import com.ainovel.module.user.service.UserService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 小说服务单测：覆盖 CRUD 触发的 ES 搜索同步消息 + 用户发布链路
 *
 * <p>重点验证：小说变更时向 MQ 发布正确的同步消息（UPSERT/DELETE），
 * 这是「DB 与 ES 最终一致」的关键一环；用户发布作品时进入待审核状态并发送审核消息。
 */
@ExtendWith(MockitoExtension.class)
class NovelServiceTest {

    @Mock
    private NovelMapper novelMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private CategoryService categoryService;
    @Mock
    private UserService userService;
    @Mock
    private MqSender mqSender;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private CacheHelper cacheHelper;
    // 以下三个依赖若不 mock，@InjectMocks 会将其注入为 null：一旦补充
    // mine / requestReshelve / requestResumeSerial / resumeSerialByAdmin / deleteByAuthor
    // 的用例（都涉及权限与申诉）就会立即 NPE。它们属于运行时才暴露的缺陷，先在此声明。
    @Mock
    private NovelAppealMapper novelAppealMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private NovelPurchaseProbe novelPurchaseProbe;

    private NovelService novelService;

    @BeforeEach
    void stubCacheThrough() {
        novelService = new NovelServiceImpl(novelMapper, novelAppealMapper, cacheHelper, chapterMapper, categoryService, userService, mqSender, stringRedisTemplate, messageService, novelPurchaseProbe);
        // 让 CacheHelper.get 直接执行 loader（回源逻辑），缓存命中与穿透/击穿/雪崩防护由 CacheHelperTest 单独验证；
        // 此处聚焦 NovelService 的回源组装与缓存失效行为。
        lenient().when(cacheHelper.get(anyString(), any(), any(), anyLong(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());
        // 详情返回前会回查作品做可见性校验（已删除/未过审一律 404），默认给一本「已上架 + 已过审」的作品
        lenient().when(novelMapper.selectCounts(anyLong())).thenReturn(visibleNovel());
    }

    /** 一本对外可见的作品：已上架 + 审核通过 */
    private static Novel visibleNovel() {
        Novel n = new Novel();
        n.setStatus(1);
        n.setAuditStatus(1);
        return n;
    }

    @Test
    @DisplayName("新增小说 → insert + 发 UPSERT 同步消息")
    void save_insert_sendsUpsert() {
        NovelForm form = new NovelForm();
        form.setTitle("测试书");
        form.setCategoryId(1L);

        // mock insert 回填雪花 ID
        doAnswer(inv -> {
            Novel d = inv.getArgument(0);
            d.setId(100L);
            return 1;
        }).when(novelMapper).insert(any(Novel.class));

        // save 内部调用 detail() 回显
        Novel saved = new Novel();
        saved.setId(100L);
        saved.setTitle("测试书");
        saved.setCategoryId(1L);
        when(novelMapper.selectById(100L)).thenReturn(saved);
        when(categoryService.getNameMap()).thenReturn(Map.of());

        novelService.save(form);

        verify(novelMapper).insert(any(Novel.class));
        verify(mqSender).sendAfterCommit(
                eq(MqConstant.SEARCH_EXCHANGE),
                eq(MqConstant.SEARCH_SYNC_ROUTING_KEY),
                argThat((SearchSyncMessage m) ->
                        "UPSERT".equals(m.getOperation()) && Long.valueOf(100L).equals(m.getNovelId())));
    }

    @Test
    @DisplayName("编辑小说 → updateById + 发 UPSERT 同步消息")
    void save_update_sendsUpsert() {
        NovelForm form = new NovelForm();
        form.setId(100L);
        form.setTitle("改名后的书");

        Novel updated = new Novel();
        updated.setId(100L);
        updated.setTitle("改名后的书");
        updated.setCategoryId(1L);
        when(novelMapper.selectById(100L)).thenReturn(updated);
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "分类"));

        novelService.save(form);

        verify(novelMapper).updateById(any(Novel.class));
        // 编辑后先更 DB 后删缓存
        verify(cacheHelper).evictAfterCommit("novel:detail:100");
        verify(mqSender).sendAfterCommit(
                eq(MqConstant.SEARCH_EXCHANGE),
                eq(MqConstant.SEARCH_SYNC_ROUTING_KEY),
                argThat((SearchSyncMessage m) -> "UPSERT".equals(m.getOperation())));
    }

    @Test
    @DisplayName("删除小说 → deleteById + 发 DELETE 同步消息")
    void delete_sendsDelete() {
        novelService.delete(100L);

        verify(novelMapper).deleteById(100L);
        verify(mqSender).sendAfterCommit(
                eq(MqConstant.SEARCH_EXCHANGE),
                eq(MqConstant.SEARCH_SYNC_ROUTING_KEY),
                argThat((SearchSyncMessage m) ->
                        "DELETE".equals(m.getOperation()) && Long.valueOf(100L).equals(m.getNovelId())));
    }

    @Test
    @DisplayName("删除小说(有封面) → 发 OSS 删除封面消息")
    void delete_withCover_sendsOssDelete() {
        Novel novel = new Novel();
        novel.setId(100L);
        novel.setCoverUrl("https://cdn.example.com/ai-novel/covers/old.png");
        when(novelMapper.selectById(100L)).thenReturn(novel);

        novelService.delete(100L);

        verify(novelMapper).deleteById(100L);
        verify(mqSender).sendAfterCommit(
                eq(MqConstant.OSS_EXCHANGE),
                eq(MqConstant.OSS_DELETE_ROUTING_KEY),
                argThat((OssDeleteMessage m) ->
                        "https://cdn.example.com/ai-novel/covers/old.png".equals(m.getUrl())));
    }

    @Test
    @DisplayName("编辑换封面 → 发 OSS 删除旧封面消息")
    void save_changeCover_sendsOssDelete() {
        NovelForm form = new NovelForm();
        form.setId(100L);
        form.setTitle("新书名");
        form.setCoverUrl("https://cdn.example.com/ai-novel/covers/new.png");

        Novel existing = new Novel();
        existing.setId(100L);
        existing.setTitle("旧书名");
        existing.setCategoryId(1L);
        existing.setCoverUrl("https://cdn.example.com/ai-novel/covers/old.png");
        when(novelMapper.selectById(100L)).thenReturn(existing);
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "分类"));

        novelService.save(form);

        verify(novelMapper).updateById(any(Novel.class));
        verify(mqSender).sendAfterCommit(
                eq(MqConstant.OSS_EXCHANGE),
                eq(MqConstant.OSS_DELETE_ROUTING_KEY),
                argThat((OssDeleteMessage m) ->
                        "https://cdn.example.com/ai-novel/covers/old.png".equals(m.getUrl())));
    }

    @Test
    @DisplayName("上下架 → updateById + 发 UPSERT 同步消息")
    void changeStatus_sendsUpsert() {
        novelService.changeStatus(100L, 0);

        verify(novelMapper).updateById(any(Novel.class));
        verify(mqSender).sendAfterCommit(
                eq(MqConstant.SEARCH_EXCHANGE),
                eq(MqConstant.SEARCH_SYNC_ROUTING_KEY),
                argThat((SearchSyncMessage m) -> "UPSERT".equals(m.getOperation())));
    }

    @Test
    @DisplayName("删除小说 → 先更 DB 后删详情缓存（Cache-Aside 一致性）")
    void delete_evictsDetailCache() {
        novelService.delete(100L);

        verify(cacheHelper).evictAfterCommit("novel:detail:100");
    }

    @Test
    @DisplayName("上下架 → 失效详情缓存")
    void changeStatus_evictsDetailCache() {
        novelService.changeStatus(100L, 1);

        verify(cacheHelper).evictAfterCommit("novel:detail:100");
    }

    @Test
    @DisplayName("用户发布作品 → 待审核+下架 + 循环建章(算字数/首章免费) + 发 AI 审核消息")
    void publish_createsWaitNovelAndSendsAuditMessage() {
        NovelPublishForm form = new NovelPublishForm();
        form.setTitle("我的 AI 作品");
        form.setCategoryId(1L);
        form.setIntro("简介");

        ChapterForm c1 = new ChapterForm();
        c1.setTitle("第一章 起源");
        c1.setContent("这是第一章的正文内容。");
        c1.setUnlockCoin(0);
        ChapterForm c2 = new ChapterForm();
        c2.setTitle("第二章 转折");
        c2.setContent("这是第二章的正文内容，篇幅更长一些。");
        c2.setUnlockCoin(5);
        form.setChapters(List.of(c1, c2));

        User author = new User();
        author.setId(9L);
        author.setUsername("rose");
        author.setNickname("Rose");

        Novel saved = new Novel();
        saved.setId(100L);
        saved.setTitle("我的 AI 作品");
        saved.setCategoryId(1L);

        doAnswer(inv -> {
            Novel d = inv.getArgument(0);
            d.setId(100L);
            return 1;
        }).when(novelMapper).insert(any(Novel.class));
        when(novelMapper.selectById(100L)).thenReturn(saved);
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "古典名著"));
        when(userService.getUser(9L)).thenReturn(author);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            novelService.publish(form);
        }

        ArgumentCaptor<Novel> novelCaptor = ArgumentCaptor.forClass(Novel.class);
        verify(novelMapper).insert(novelCaptor.capture());
        assertEquals(0, novelCaptor.getValue().getAuditStatus());
        assertEquals(0, novelCaptor.getValue().getStatus());
        assertEquals(9L, novelCaptor.getValue().getUserId());
        assertEquals("Rose", novelCaptor.getValue().getAuthor());
        assertEquals(2, novelCaptor.getValue().getTotalChapters());
        long expectedWords = c1.getContent().length() + c2.getContent().length();
        assertEquals(Long.valueOf(expectedWords), novelCaptor.getValue().getWordCount());
        // 整本价 = 付费章解锁币之和(5) × 0.6 → 3，不能为 0（否则「整本解锁」可 0 币买断绕过付费墙）
        assertEquals(3, novelCaptor.getValue().getCoinPrice());

        ArgumentCaptor<Chapter> epCaptor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper, times(2)).insert(epCaptor.capture());
        List<Chapter> inserted = epCaptor.getAllValues();
        assertEquals(100L, inserted.get(0).getNovelId());
        assertEquals(1, inserted.get(0).getChapterNo());
        assertEquals("这是第一章的正文内容。", inserted.get(0).getContent());
        assertEquals(0, inserted.get(0).getUnlockCoin()); // 首章强制免费
        assertEquals(2, inserted.get(1).getChapterNo());
        assertEquals(5, inserted.get(1).getUnlockCoin()); // 其余按用户设定

        verify(mqSender).sendAfterCommit(
                eq(MqConstant.AI_EXCHANGE),
                eq(MqConstant.AI_AUDIT_ROUTING_KEY),
                argThat((AiAuditMessage m) -> Long.valueOf(100L).equals(m.getNovelId())));
    }

    @Test
    @DisplayName("发布作品 → 分类不存在时抛参数错误")
    void publish_unknownCategory_throws() {
        NovelPublishForm form = new NovelPublishForm();
        form.setTitle("我的 AI 作品");
        form.setCategoryId(999L);
        when(categoryService.getNameMap()).thenReturn(Map.of(1L, "都市"));

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> novelService.publish(form));
            assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        }
        verify(novelMapper, never()).insert(any(Novel.class));
    }

    @Test
    @DisplayName("详情不存在 → 抛 NOVEL_NOT_FOUND")
    void detail_notFound_throws() {
        when(novelMapper.selectById(100L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> novelService.detail(100L));
        assertEquals(ErrorCode.NOVEL_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("详情 → 补全总字数 + 首章 ID")
    void detail_fillsWordCountAndFirstChapterId() {
        Novel novel = new Novel();
        novel.setId(100L);
        novel.setTitle("测试书");
        novel.setCategoryId(1L);
        novel.setWordCount(12345L);
        when(novelMapper.selectById(100L)).thenReturn(novel);
        when(categoryService.getNameMap()).thenReturn(Map.of());
        Chapter first = new Chapter();
        first.setId(200L);
        when(chapterMapper.selectOne(any())).thenReturn(first);

        NovelVO vo = novelService.detail(100L);

        assertEquals(Long.valueOf(12345L), vo.getWordCount());
        assertEquals(200L, vo.getFirstChapterId());
    }

    @Test
    @DisplayName("点赞 → 首次点赞自增并返回 liked=true + 最新计数")
    void like_firstTime_incr() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("novel:like:9:100"), eq("1"))).thenReturn(true);
            Novel row = new Novel();
            row.setLikeCount(11L);
            when(novelMapper.selectCounts(100L)).thenReturn(row);

            LikeVO vo = novelService.like(100L);

            verify(novelMapper).incrLikeCount(100L);
            assertTrue(vo.getLiked());
            assertEquals(Long.valueOf(11L), vo.getLikeCount());
        }
    }

    @Test
    @DisplayName("取消点赞 → 已赞则删 key 自减并返回 liked=false")
    void like_again_unlikes() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(eq("novel:like:9:100"), eq("1"))).thenReturn(false);
            Novel row = new Novel();
            row.setLikeCount(9L);
            when(novelMapper.selectCounts(100L)).thenReturn(row);

            LikeVO vo = novelService.like(100L);

            verify(stringRedisTemplate).delete("novel:like:9:100");
            verify(novelMapper).decrLikeCount(100L);
            verify(novelMapper, never()).incrLikeCount(anyLong());
            assertFalse(vo.getLiked());
            assertEquals(Long.valueOf(9L), vo.getLikeCount());
        }
    }

    @Test
    @DisplayName("详情 → 已登录且已赞回填 liked=true")
    void detail_fillsLiked() {
        Novel novel = new Novel();
        novel.setId(100L);
        novel.setTitle("测试书");
        novel.setCategoryId(1L);
        novel.setWordCount(100L);
        when(novelMapper.selectById(100L)).thenReturn(novel);
        when(categoryService.getNameMap()).thenReturn(Map.of());
        when(chapterMapper.selectOne(any())).thenReturn(null);
        Novel counts = new Novel();
        counts.setReadCount(10L);
        counts.setLikeCount(5L);
        counts.setStatus(1);
        counts.setAuditStatus(1);   // 详情返回前要做可见性校验，得是一本对外可见的作品
        when(novelMapper.selectCounts(100L)).thenReturn(counts);

        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(9L);
            when(stringRedisTemplate.hasKey("novel:like:9:100")).thenReturn(true);

            NovelVO vo = novelService.detail(100L);

            assertTrue(vo.getLiked());
            assertEquals(Long.valueOf(5L), vo.getLikeCount());
        }
    }

    @Test
    @DisplayName("记录阅读 → 首次计入：阅读量 +1，并通知榜单加热度")
    void recordRead_firstTime_incrementsAndNotifies() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(true);

            assertTrue(novelService.recordRead(100L), "首次阅读应返回 true");

            verify(novelMapper).incrReadCount(100L);
            verify(mqSender).sendAfterCommit(eq(MqConstant.RANK_READ_EXCHANGE),
                    eq(MqConstant.RANK_READ_ROUTING_KEY), any(NovelReadMessage.class));
        }
    }

    @Test
    @DisplayName("记录阅读 → 24h 内重复阅读：不计数也不发消息（防刷）")
    void recordRead_duplicate_skips() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(9L);
            when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
            when(valueOps.setIfAbsent(anyString(), eq("1"), any())).thenReturn(false);

            assertFalse(novelService.recordRead(100L), "去重命中应返回 false");

            verify(novelMapper, never()).incrReadCount(anyLong());
            verifyNoInteractions(mqSender);
        }
    }
}
