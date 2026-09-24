package com.ainovel.module.bookshelf.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.bookshelf.dao.BookshelfMapper;
import com.ainovel.module.bookshelf.domain.entity.Bookshelf;
import com.ainovel.module.bookshelf.service.impl.BookshelfServiceImpl;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.service.NovelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 书架服务单测：收藏幂等、小说存在性校验、移出
 */
@ExtendWith(MockitoExtension.class)
class BookshelfServiceTest {

    @Mock
    private BookshelfMapper bookshelfMapper;

    @Mock
    private NovelService novelService;

    private BookshelfService bookshelfService;

    @BeforeEach
    void initService() {
        bookshelfService = new BookshelfServiceImpl(bookshelfMapper, novelService);
    }

    private static final Long USER_ID = 1L;
    private static final Long NOVEL_ID = 100L;

    @Test
    @DisplayName("加入书架 → 首次收藏落库")
    void add_inserts() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            Novel novel = new Novel();
            novel.setId(NOVEL_ID);
            novel.setStatus(1);   // 在架才允许收藏
            when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
            when(bookshelfMapper.selectCount(any())).thenReturn(0L);

            bookshelfService.add(NOVEL_ID);

            ArgumentCaptor<Bookshelf> captor = ArgumentCaptor.forClass(Bookshelf.class);
            verify(bookshelfMapper).insert(captor.capture());
            assertEquals(USER_ID, captor.getValue().getUserId());
            assertEquals(NOVEL_ID, captor.getValue().getNovelId());
        }
    }

    @Test
    @DisplayName("加入书架 → 已收藏则幂等跳过")
    void add_idempotentSkip() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            Novel novel = new Novel();
            novel.setId(NOVEL_ID);
            novel.setStatus(1);   // 在架才允许收藏
            when(novelService.getNovel(NOVEL_ID)).thenReturn(novel);
            when(bookshelfMapper.selectCount(any())).thenReturn(1L);

            bookshelfService.add(NOVEL_ID);

            verify(bookshelfMapper, never()).insert(any(Bookshelf.class));
        }
    }

    @Test
    @DisplayName("加入书架 → 小说不存在抛 NOVEL_NOT_FOUND")
    void add_novelNotFound_throws() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(novelService.getNovel(NOVEL_ID)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> bookshelfService.add(NOVEL_ID));
            assertEquals(ErrorCode.NOVEL_NOT_FOUND, ex.getErrorCode());
        }
    }

    @Test
    @DisplayName("移出书架 → 物理删除")
    void remove_deletes() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            bookshelfService.remove(NOVEL_ID);

            verify(bookshelfMapper).deleteByUserAndNovel(USER_ID, NOVEL_ID);
        }
    }

    @Test
    @DisplayName("是否已收藏 → 有记录返回 true")
    void contains_returnsTrue() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(bookshelfMapper.selectCount(any())).thenReturn(1L);

            assertTrue(bookshelfService.contains(NOVEL_ID));
        }
    }
}
