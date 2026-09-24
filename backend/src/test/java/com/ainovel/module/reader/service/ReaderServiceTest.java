package com.ainovel.module.reader.service;

import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.reader.dao.ReaderPreferenceMapper;
import com.ainovel.module.reader.dao.ReadingProgressMapper;
import com.ainovel.module.reader.domain.entity.ReaderPreference;
import com.ainovel.module.reader.domain.entity.ReadingProgress;
import com.ainovel.module.reader.domain.form.PreferenceForm;
import com.ainovel.module.reader.domain.form.ProgressForm;
import com.ainovel.module.reader.domain.vo.PreferenceVO;
import com.ainovel.module.reader.domain.vo.ProgressVO;
import com.ainovel.module.reader.service.impl.ReaderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 阅读进度 / 偏好服务单测：每用户一行，幂等 upsert
 *
 * <p>纯 JUnit5 + Mockito，无 Spring 容器。外部依赖（两个 Mapper）全部 mock，
 * 登录态用 mockStatic 兜住 LoginUserUtil.getUserId()。
 */
@ExtendWith(MockitoExtension.class)
class ReaderServiceTest {

    @Mock
    private ReadingProgressMapper progressMapper;

    @Mock
    private ReaderPreferenceMapper preferenceMapper;

    private ReaderService readerService;

    @BeforeEach
    void initService() {
        readerService = new ReaderServiceImpl(progressMapper, preferenceMapper);
    }

    private static final Long USER_ID = 1L;
    private static final Long NOVEL_ID = 100L;
    private static final Long CHAPTER_ID = 200L;

    // ============ 阅读进度 ============

    @Test
    @DisplayName("保存进度 → 首次插入，写入 userId")
    void saveProgress_firstInsert() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(progressMapper.selectOne(any())).thenReturn(null);

            readerService.saveProgress(buildProgressForm());

            ArgumentCaptor<ReadingProgress> captor = ArgumentCaptor.forClass(ReadingProgress.class);
            verify(progressMapper).insert(captor.capture());
            ReadingProgress saved = captor.getValue();
            assertEquals(USER_ID, saved.getUserId());
            assertEquals(NOVEL_ID, saved.getNovelId());
            assertEquals(CHAPTER_ID, saved.getChapterId());
            assertEquals("重生之我在小说当顶流", saved.getNovelTitle());
            assertEquals(12, saved.getChapterNo());
            verify(progressMapper, never()).updateById(any(ReadingProgress.class));
        }
    }

    @Test
    @DisplayName("保存进度 → 已存在则 update（幂等，复用原 id）")
    void saveProgress_updateExisting() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReadingProgress exist = new ReadingProgress();
            exist.setId(999L);
            exist.setUserId(USER_ID);
            when(progressMapper.selectOne(any())).thenReturn(exist);

            readerService.saveProgress(buildProgressForm());

            ArgumentCaptor<ReadingProgress> captor = ArgumentCaptor.forClass(ReadingProgress.class);
            verify(progressMapper).updateById(captor.capture());
            ReadingProgress saved = captor.getValue();
            assertEquals(999L, saved.getId());
            assertEquals(NOVEL_ID, saved.getNovelId());
            verify(progressMapper, never()).insert(any(ReadingProgress.class));
        }
    }

    @Test
    @DisplayName("保存进度 → 云端更新(clientTime 更大)则忽略 stale 推送")
    void saveProgress_stalePush_ignored() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReadingProgress exist = new ReadingProgress();
            exist.setId(999L);
            exist.setUserId(USER_ID);
            exist.setClientTime(2000L); // 云端更新
            when(progressMapper.selectOne(any())).thenReturn(exist);

            ProgressForm form = buildProgressForm();
            form.setClientTime(1000L); // 本地较旧
            readerService.saveProgress(form);

            verify(progressMapper, never()).updateById(any(ReadingProgress.class));
            verify(progressMapper, never()).insert(any(ReadingProgress.class));
        }
    }

    @Test
    @DisplayName("保存进度 → 本地更新(clientTime 更大)则覆盖云端")
    void saveProgress_fresherPush_updates() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReadingProgress exist = new ReadingProgress();
            exist.setId(999L);
            exist.setUserId(USER_ID);
            exist.setClientTime(1000L);
            when(progressMapper.selectOne(any())).thenReturn(exist);

            ProgressForm form = buildProgressForm();
            form.setClientTime(2000L);
            readerService.saveProgress(form);

            ArgumentCaptor<ReadingProgress> captor = ArgumentCaptor.forClass(ReadingProgress.class);
            verify(progressMapper).updateById(captor.capture());
            assertEquals(999L, captor.getValue().getId());
            assertEquals(2000L, captor.getValue().getClientTime());
            verify(progressMapper, never()).insert(any(ReadingProgress.class));
        }
    }

    @Test
    @DisplayName("获取进度 → 无记录返回 null（前端保留本地并回推）")
    void getProgress_null() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(progressMapper.selectOne(any())).thenReturn(null);

            assertNull(readerService.getProgress());
        }
    }

    @Test
    @DisplayName("获取进度 → 有记录返回 VO")
    void getProgress_returnsVO() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReadingProgress p = new ReadingProgress();
            p.setUserId(USER_ID);
            p.setNovelId(NOVEL_ID);
            p.setChapterId(CHAPTER_ID);
            p.setNovelTitle("重生之我在小说当顶流");
            p.setChapterNo(12);
            p.setMode("scroll");
            when(progressMapper.selectOne(any())).thenReturn(p);

            ProgressVO vo = readerService.getProgress();

            assertNotNull(vo);
            assertEquals(NOVEL_ID, vo.getNovelId());
            assertEquals(CHAPTER_ID, vo.getChapterId());
            assertEquals("重生之我在小说当顶流", vo.getNovelTitle());
            assertEquals("scroll", vo.getMode());
        }
    }

    @Test
    @DisplayName("保存进度 → 并发首插撞唯一键，重查转 update（幂等）")
    void saveProgress_concurrentInsert_retryUpdate() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReadingProgress fresh = new ReadingProgress();
            fresh.setId(999L);
            fresh.setUserId(USER_ID);
            // 首次 selectOne 判空 → null；catch 后重查 → 已存在
            when(progressMapper.selectOne(any())).thenReturn(null, fresh);
            when(progressMapper.insert(any(ReadingProgress.class))).thenThrow(new DuplicateKeyException("dup"));

            readerService.saveProgress(buildProgressForm());

            ArgumentCaptor<ReadingProgress> captor = ArgumentCaptor.forClass(ReadingProgress.class);
            verify(progressMapper).updateById(captor.capture());
            assertEquals(999L, captor.getValue().getId());
        }
    }

    @Test
    @DisplayName("保存进度 → 客户端时钟超前过多被钳制到服务端时间")
    void saveProgress_futureClientTime_clamped() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReadingProgress exist = new ReadingProgress();
            exist.setId(999L);
            exist.setUserId(USER_ID);
            exist.setClientTime(1000L);
            when(progressMapper.selectOne(any())).thenReturn(exist);

            long future = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
            ProgressForm form = buildProgressForm();
            form.setClientTime(future);
            readerService.saveProgress(form);

            ArgumentCaptor<ReadingProgress> captor = ArgumentCaptor.forClass(ReadingProgress.class);
            verify(progressMapper).updateById(captor.capture());
            long saved = captor.getValue().getClientTime();
            assertTrue(saved < future, "未来值应被钳制到服务端时间");
            assertTrue(saved <= System.currentTimeMillis());
        }
    }

    // ============ 阅读偏好 ============

    @Test
    @DisplayName("保存偏好 → 首次插入，写入 userId")
    void savePreference_firstInsert() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(preferenceMapper.selectOne(any())).thenReturn(null);

            readerService.savePreference(buildPreferenceForm());

            ArgumentCaptor<ReaderPreference> captor = ArgumentCaptor.forClass(ReaderPreference.class);
            verify(preferenceMapper).insert(captor.capture());
            ReaderPreference saved = captor.getValue();
            assertEquals(USER_ID, saved.getUserId());
            assertEquals(20, saved.getFontSize());
            assertEquals("sepia", saved.getTheme());
            verify(preferenceMapper, never()).updateById(any(ReaderPreference.class));
        }
    }

    @Test
    @DisplayName("保存偏好 → 已存在则 update（幂等）")
    void savePreference_updateExisting() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReaderPreference exist = new ReaderPreference();
            exist.setId(888L);
            exist.setUserId(USER_ID);
            when(preferenceMapper.selectOne(any())).thenReturn(exist);

            readerService.savePreference(buildPreferenceForm());

            ArgumentCaptor<ReaderPreference> captor = ArgumentCaptor.forClass(ReaderPreference.class);
            verify(preferenceMapper).updateById(captor.capture());
            ReaderPreference saved = captor.getValue();
            assertEquals(888L, saved.getId());
            assertEquals("sepia", saved.getTheme());
            verify(preferenceMapper, never()).insert(any(ReaderPreference.class));
        }
    }

    @Test
    @DisplayName("保存偏好 → 并发首插撞唯一键，重查转 update（幂等）")
    void savePreference_concurrentInsert_retryUpdate() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReaderPreference fresh = new ReaderPreference();
            fresh.setId(888L);
            fresh.setUserId(USER_ID);
            when(preferenceMapper.selectOne(any())).thenReturn(null, fresh);
            when(preferenceMapper.insert(any(ReaderPreference.class))).thenThrow(new DuplicateKeyException("dup"));

            readerService.savePreference(buildPreferenceForm());

            ArgumentCaptor<ReaderPreference> captor = ArgumentCaptor.forClass(ReaderPreference.class);
            verify(preferenceMapper).updateById(captor.capture());
            assertEquals(888L, captor.getValue().getId());
        }
    }

    @Test
    @DisplayName("获取偏好 → 无记录返回 null")
    void getPreference_null() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            when(preferenceMapper.selectOne(any())).thenReturn(null);

            assertNull(readerService.getPreference());
        }
    }

    @Test
    @DisplayName("获取偏好 → 有记录返回 VO")
    void getPreference_returnsVO() {
        try (MockedStatic<LoginUserUtil> mocked = mockStatic(LoginUserUtil.class)) {
            mocked.when(LoginUserUtil::getUserId).thenReturn(USER_ID);
            ReaderPreference p = new ReaderPreference();
            p.setUserId(USER_ID);
            p.setFontSize(20);
            p.setTheme("night");
            p.setLineHeight(2.0);
            when(preferenceMapper.selectOne(any())).thenReturn(p);

            PreferenceVO vo = readerService.getPreference();

            assertNotNull(vo);
            assertEquals(20, vo.getFontSize());
            assertEquals("night", vo.getTheme());
            assertEquals(2.0, vo.getLineHeight());
        }
    }

    // ============ 工具方法 ============

    private ProgressForm buildProgressForm() {
        ProgressForm form = new ProgressForm();
        form.setNovelId(NOVEL_ID);
        form.setChapterId(CHAPTER_ID);
        form.setNovelTitle("重生之我在小说当顶流");
        form.setChapterNo(12);
        form.setChapterTitle("第 12 章");
        form.setMode("scroll");
        form.setScrollTop(800);
        form.setPageNo(0);
        return form;
    }

    private PreferenceForm buildPreferenceForm() {
        PreferenceForm form = new PreferenceForm();
        form.setFontSize(20);
        form.setFontFamily("hei");
        form.setLineHeight(2.0);
        form.setColumnWidth(720);
        form.setTheme("sepia");
        form.setBrightness(0.9);
        form.setMode("scroll");
        form.setAutoSpeed(60);
        return form;
    }
}
