package com.ainovel.module.comment.service;

import com.ainovel.common.audit.SensitiveWordFilter;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.comment.dao.CommentMapper;
import com.ainovel.module.comment.domain.entity.Comment;
import com.ainovel.module.comment.domain.form.CommentForm;
import com.ainovel.module.comment.domain.vo.CommentVO;
import com.ainovel.module.comment.service.impl.CommentServiceImpl;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.novel.service.NovelService;
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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 评论服务单测：发评论、删自己的、越权删除防护、点赞去重、列表回填点赞状态、管理端删除
 */
@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private UserService userService;

    // 章评会走 requireChapterCommentable → chapterService.getById。
    // 此处若不 mock（现有用例的 form 只设 novelId、chapterId 为 null，因此绕过了校验），
    // 任何补充「带 chapterId 的章评用例」都会立即 NPE；章评是读者主入口，先在此声明。
    @Mock
    private ChapterService chapterService;

    // 管理端分页要回填作品标题（novelService.listNovels）
    @Mock
    private NovelService novelService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SensitiveWordFilter sensitiveWordFilter;

    private CommentService commentService;

    private static final Long USER_ID = 1L;
    private static final Long NOVEL_ID = 100L;

    @BeforeEach
    void setUpRedis() {
        commentService = new CommentServiceImpl(commentMapper, userService, chapterService, novelService, stringRedisTemplate, sensitiveWordFilter);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("发评论 → 落库 + 回填作者昵称")
    void add_insertsComment() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(userService.getNicknameMap(any())).thenReturn(Map.of(USER_ID, "小明"));
            when(sensitiveWordFilter.contains("写得太好了")).thenReturn(false);

            CommentForm form = new CommentForm();
            form.setNovelId(NOVEL_ID);
            form.setContent("写得太好了");
            CommentVO vo = commentService.add(form);

            ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
            verify(commentMapper).insert(captor.capture());
            assertEquals(USER_ID, captor.getValue().getUserId());
            assertEquals(NOVEL_ID, captor.getValue().getNovelId());
            assertEquals(0, captor.getValue().getLikeCount());
            assertEquals("小明", vo.getAuthorName());
        }
    }

    @Test
    @DisplayName("删自己的顶层评论 → 软删本体 + 级联删回复")
    void delete_ownTopLevel_cascade() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            Comment own = new Comment();
            own.setId(10L);
            own.setUserId(USER_ID);
            own.setParentId(null);
            when(commentMapper.selectById(10L)).thenReturn(own);
            // 级联删回复前会先取一次回复 id（用于清点赞状态寄存器），这里返回空列表
            when(commentMapper.selectList(any())).thenReturn(List.of());

            commentService.delete(10L);

            verify(commentMapper).deleteById(10L);
            verify(commentMapper).delete(any());
        }
    }

    @Test
    @DisplayName("删他人评论 → 抛 NOT_FOUND（防越权）")
    void delete_othersComment_throws() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            Comment others = new Comment();
            others.setId(10L);
            others.setUserId(2L);
            when(commentMapper.selectById(10L)).thenReturn(others);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> commentService.delete(10L));
            assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
            verify(commentMapper, never()).deleteById(anyLong());
        }
    }

    @Test
    @DisplayName("点赞 → 首次点赞自增，返回已赞")
    void like_firstLike_incr() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(commentMapper.selectById(10L)).thenReturn(commentOf(10L));
            when(hashOperations.putIfAbsent(anyString(), any(), any())).thenReturn(true);

            assertTrue(commentService.like(10L));

            verify(commentMapper).incrLikeCount(10L);
            verify(commentMapper, never()).decrLikeCount(any());
        }
    }

    @Test
    @DisplayName("点赞 → 再点一次是取消（自减 + 返回未赞）")
    void like_secondTime_cancels() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(commentMapper.selectById(10L)).thenReturn(commentOf(10L));
            when(hashOperations.putIfAbsent(anyString(), any(), any())).thenReturn(false);

            assertFalse(commentService.like(10L));

            verify(commentMapper).decrLikeCount(10L);
            verify(commentMapper, never()).incrLikeCount(any());
        }
    }

    @Test
    @DisplayName("点赞不存在的评论 → 抛 NOT_FOUND")
    void like_missingComment_throws() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(commentMapper.selectById(404L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> commentService.like(404L));
            assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
            verify(commentMapper, never()).incrLikeCount(any());
        }
    }

    @Test
    @DisplayName("列表回填点赞状态：已赞的为 true、未赞的为 false（一次性 pipeline 取回）")
    void page_fillsLikedState() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(USER_ID);
            doReturn(pageOf(commentOf(10L), commentOf(11L))).when(commentMapper).selectPage(any(), any());
            when(commentMapper.selectList(any())).thenReturn(List.of());
            doReturn(List.of(true, false)).when(stringRedisTemplate).executePipelined(any(RedisCallback.class));

            PageResult<CommentVO> result = commentService.page(1, 10, NOVEL_ID);

            assertTrue(result.getList().get(0).getLiked());
            assertFalse(result.getList().get(1).getLiked());
        }
    }

    @Test
    @DisplayName("游客拉列表：liked 全为 false，且不访问 Redis（评论列表对匿名开放）")
    void page_guest_fillsLikedFalseWithoutRedis() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(null);
            doReturn(pageOf(commentOf(10L))).when(commentMapper).selectPage(any(), any());
            when(commentMapper.selectList(any())).thenReturn(List.of());

            PageResult<CommentVO> result = commentService.page(1, 10, NOVEL_ID);

            // liked 为 null 时此处会因拆箱失败而报错，可钉住「必须显式置 false」
            assertFalse(result.getList().get(0).getLiked());
            verify(stringRedisTemplate, never()).executePipelined(any(RedisCallback.class));
        }
    }

    @Test
    @DisplayName("管理端分页：一次查询回填作品标题与章号，书评的章号为 null")
    void adminPage_fillsNovelTitleAndChapterNo() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserIdOrNull).thenReturn(USER_ID);
            Comment bookReview = commentOf(10L);
            Comment chapterReview = commentOf(11L);
            chapterReview.setChapterId(200L);
            doReturn(pageOf(bookReview, chapterReview)).when(commentMapper).selectPage(any(), any());
            when(novelService.listNovels(anyCollection()))
                    .thenReturn(List.of(novelOf(NOVEL_ID, "雨夜铜钱")));
            when(chapterService.listByIds(anyCollection()))
                    .thenReturn(List.of(chapterOf(200L, 7)));

            PageResult<CommentVO> result = commentService.adminPage(1, 30, "太差", null, null);

            assertEquals("雨夜铜钱", result.getList().get(0).getNovelTitle());
            assertNull(result.getList().get(0).getChapterNo());
            assertEquals(7, result.getList().get(1).getChapterNo());
        }
    }

    @Test
    @DisplayName("管理端删除他人评论 → 允许，并级联删除其回复")
    void adminDelete_othersComment_allowed() {
        Comment others = commentOf(10L);
        others.setUserId(2L);
        when(commentMapper.selectById(10L)).thenReturn(others);
        when(commentMapper.selectList(any())).thenReturn(List.of());

        commentService.adminDelete(10L);

        verify(commentMapper).deleteById(10L);
        verify(commentMapper).delete(any());
    }

    @Test
    @DisplayName("管理端删除不存在的评论 → 抛 NOT_FOUND，不静默成功")
    void adminDelete_missing_throws() {
        when(commentMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> commentService.adminDelete(404L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(commentMapper, never()).deleteById(anyLong());
    }

    private Comment commentOf(Long id) {
        Comment c = new Comment();
        c.setId(id);
        c.setUserId(USER_ID);
        c.setNovelId(NOVEL_ID);
        return c;
    }

    private Page<Comment> pageOf(Comment... comments) {
        Page<Comment> page = new Page<>(1, 10);
        page.setRecords(List.of(comments));
        page.setTotal(comments.length);
        return page;
    }

    private Novel novelOf(Long id, String title) {
        Novel n = new Novel();
        n.setId(id);
        n.setTitle(title);
        return n;
    }

    private Chapter chapterOf(Long id, int chapterNo) {
        Chapter c = new Chapter();
        c.setId(id);
        c.setChapterNo(chapterNo);
        return c;
    }
}
