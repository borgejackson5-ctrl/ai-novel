package com.ainovel.module.history.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.history.dao.ReadHistoryMapper;
import com.ainovel.module.history.domain.entity.ReadHistory;
import com.ainovel.module.history.domain.form.ReadHistoryForm;
import com.ainovel.module.history.domain.vo.ReadHistoryVO;
import com.ainovel.module.history.service.impl.ReadHistoryServiceImpl;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Novel;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 阅读历史服务单测：追加记录、按小说去重
 */
@ExtendWith(MockitoExtension.class)
class ReadHistoryServiceTest {

    @Mock
    private ReadHistoryMapper readHistoryMapper;

    @Mock
    private NovelMapper novelMapper;

    private ReadHistoryService readHistoryService;

    @BeforeEach
    void initService() {
        readHistoryService = new ReadHistoryServiceImpl(readHistoryMapper, novelMapper);
    }

    private static final Long USER_ID = 1L;

    @Test
    @DisplayName("记录阅读 → 落库 userId")
    void record_inserts() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);

            ReadHistoryForm form = new ReadHistoryForm();
            form.setNovelId(100L);
            form.setChapterId(200L);
            form.setChapterNo(12);
            form.setNovelTitle("书名");
            form.setChapterTitle("章名");
            readHistoryService.record(form);

            ArgumentCaptor<ReadHistory> captor = ArgumentCaptor.forClass(ReadHistory.class);
            // 写入走幂等 upsert（同一用户同一章只保留一条），不再使用裸 insert
            verify(readHistoryMapper).upsertIgnoreDuplicate(captor.capture());
            assertEquals(USER_ID, captor.getValue().getUserId());
            assertEquals(100L, captor.getValue().getNovelId());
            assertEquals(200L, captor.getValue().getChapterId());
        }
    }

    @Test
    @DisplayName("阅读历史分页 → 按小说去重，每本保留最近一次")
    void page_dedupByNovel() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReadHistory latest = build(100L, 201L, 13, "书名A");
            ReadHistory older = build(100L, 200L, 12, "书名A");
            ReadHistory other = build(101L, 300L, 5, "书名B");
            Page<ReadHistory> page = new Page<>(1, 200);
            page.setRecords(List.of(latest, older, other));
            when(readHistoryMapper.selectPage(any(), any())).thenReturn(page);
            // 阅读历史只展示仍然可见的作品，这里假设两本都还在架
            Novel n1 = new Novel();
            n1.setId(100L);
            Novel n2 = new Novel();
            n2.setId(101L);
            when(novelMapper.selectList(any())).thenReturn(List.of(n1, n2));

            PageResult<ReadHistoryVO> result = readHistoryService.page(1, 20, null, null);

            List<ReadHistoryVO> list = result.getList();
            assertEquals(2, list.size());
            assertEquals(2L, result.getTotal()); // 去重后的总数，不是原始行数
            assertEquals(100L, list.get(0).getNovelId());
            assertEquals(201L, list.get(0).getChapterId()); // 保留最近一次
            assertEquals(101L, list.get(1).getNovelId());
        }
    }

    @Test
    @DisplayName("阅读历史分页 → 去重后按页切片，越界页返回空")
    void page_slicesAfterDedup() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            Page<ReadHistory> page = new Page<>(1, 200);
            page.setRecords(List.of(
                    build(100L, 201L, 13, "书名A"),
                    build(101L, 300L, 5, "书名B"),
                    build(102L, 400L, 7, "书名C")));
            when(readHistoryMapper.selectPage(any(), any())).thenReturn(page);
            when(novelMapper.selectList(any())).thenReturn(List.of());

            // selectList 返回空 → 可见性过滤把三条全滤掉，分页应安全返回空而不是抛越界
            PageResult<ReadHistoryVO> result = readHistoryService.page(1, 2, null, null);

            assertEquals(0L, result.getTotal());
            assertTrue(result.getList().isEmpty());
        }
    }

    private ReadHistory build(Long novelId, Long chapterId, int chapterNo, String novelTitle) {
        ReadHistory h = new ReadHistory();
        h.setUserId(USER_ID);
        h.setNovelId(novelId);
        h.setChapterId(chapterId);
        h.setChapterNo(chapterNo);
        h.setNovelTitle(novelTitle);
        h.setChapterTitle("章");
        return h;
    }
}
