package com.ainovel.module.comment.service;

import com.ainovel.common.audit.SensitiveWordFilter;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.comment.dao.CommentMapper;
import com.ainovel.module.comment.domain.entity.Comment;
import com.ainovel.module.comment.domain.form.CommentForm;
import com.ainovel.module.comment.domain.vo.CommentVO;
import com.ainovel.module.comment.service.impl.CommentServiceImpl;
import com.ainovel.module.novel.service.ChapterService;
import com.ainovel.module.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 评论服务单测：发评论、删自己的、越权删除防护、点赞去重
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
        commentService = new CommentServiceImpl(commentMapper, userService, chapterService, stringRedisTemplate, sensitiveWordFilter);
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

    private Comment commentOf(Long id) {
        Comment c = new Comment();
        c.setId(id);
        c.setUserId(USER_ID);
        return c;
    }
}
