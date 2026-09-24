package com.ainovel.module.novel.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.ratelimit.DailyQuotaLimiter;
import com.ainovel.common.client.DashScopeClient;
import com.ainovel.module.novel.service.impl.CoverServiceImpl;
import com.ainovel.module.oss.service.OssService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 封面上传 / 生成单测。
 *
 * <p>需要测试的原因：上传入口是唯一由用户直接提供字节的入口，而「只校验后缀」是该类入口最典型的问题：
 * 把任意文件改名为 .png 即可写入对象存储。这套校验（大小 → 后缀白名单 → 文件头魔数）
 * 一旦哪一层被改坏，表现为「上传照常成功」，没有任何报错。
 */
@ExtendWith(MockitoExtension.class)
class CoverServiceTest {

    private static final String COVER_DIR = "ai-novel/covers";
    private static final int DAILY_LIMIT = 20;

    /** 文件头魔数样本：用于识别改过后缀的图片的真实类型 */
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2};
    private static final byte[] GIF = {0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 2};
    /** WEBP 是 RIFF 容器：前 4 字节为 RIFF，第 8-11 字节才是 WEBP（中间 4 字节为长度） */
    private static final byte[] WEBP = {0x52, 0x49, 0x46, 0x46, 0x24, 0, 0, 0, 0x57, 0x45, 0x42, 0x50, 1};
    private static final byte[] PLAIN_TEXT = "#!/bin/sh\necho hello\n".getBytes();

    @Mock
    private DashScopeClient dashScopeClient;
    @Mock
    private OssService ossService;
    @Mock
    private DailyQuotaLimiter dailyQuotaLimiter;

    private CoverService coverService;

    @BeforeEach
    void setUp() {
        coverService = new CoverServiceImpl(dashScopeClient, ossService, dailyQuotaLimiter);
        // @Value 在单测里不会注入，必须手动设；否则 coverDailyLimit 是 0，额度判定全是拒绝
        ReflectionTestUtils.setField(coverService, "coverDailyLimit", DAILY_LIMIT);
    }

    private MockMultipartFile file(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "image/png", content);
    }

    // ---------- AI 生成 ----------

    @Test
    @DisplayName("生成：描述为空 → 直接拒绝，且不消耗额度、不调文生图")
    void generate_blankPrompt() {
        BusinessException ex = assertThrows(BusinessException.class, () -> coverService.generate("   "));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verifyNoInteractions(dailyQuotaLimiter, dashScopeClient, ossService);
    }

    @Test
    @DisplayName("生成：额度用尽 → 抛封面额度错误，且不调文生图（不产生费用）")
    void generate_quotaExhausted() {
        when(dailyQuotaLimiter.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> coverService.generate("一只猫"));

        assertEquals(ErrorCode.AI_COVER_LIMIT, ex.getErrorCode());
        verifyNoInteractions(dashScopeClient, ossService);
    }

    @Test
    @DisplayName("生成：额度计数器必须与文本 Key 分开（否则改文案会把封面额度一起耗掉）")
    void generate_usesDedicatedQuotaCounter() {
        when(dailyQuotaLimiter.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(false);

        assertThrows(BusinessException.class, () -> coverService.generate("一只猫"));

        ArgumentCaptor<String> prefixCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> limitCap = ArgumentCaptor.forClass(Long.class);
        verify(dailyQuotaLimiter).tryAcquire(prefixCap.capture(), limitCap.capture(), anyLong());
        assertEquals("ai:cover:usage:", prefixCap.getValue());
        assertEquals(DAILY_LIMIT, limitCap.getValue().longValue(),
                "配置里的每日上限没透传给限流器，改配置会失效");
    }

    @Test
    @DisplayName("生成：描述会补上统一风格后缀，并落到固定的封面目录")
    void generate_appendsStyleAndUploadsToCoverDir() {
        when(dailyQuotaLimiter.tryAcquire(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(dashScopeClient.textToImage(anyString())).thenReturn(PNG);
        when(ossService.upload(any(byte[].class), eq(COVER_DIR), eq("png"), eq("image/png")))
                .thenReturn("https://cdn.example.com/c.png");

        String url = coverService.generate("  一只猫坐在书堆上  ");

        assertEquals("https://cdn.example.com/c.png", url);
        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);
        verify(dashScopeClient).textToImage(promptCap.capture());
        assertTrue(promptCap.getValue().startsWith("一只猫"),
                "用户描述应当被 trim 后放在最前，实际：" + promptCap.getValue());
        assertTrue(promptCap.getValue().contains("竖版小说封面"),
                "统一风格后缀丢了，出图观感会不一致。实际：" + promptCap.getValue());
    }

    // ---------- 用户上传：第一层 大小 / 后缀 ----------

    @Test
    @DisplayName("上传：空文件 → 拒绝")
    void upload_empty() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> coverService.upload(file("a.png", new byte[0])));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verifyNoInteractions(ossService);
    }

    @Test
    @DisplayName("上传：超过 5MB → 拒绝")
    void upload_oversize() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coverService.upload(file("a.png", big)));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verifyNoInteractions(ossService);
    }

    @Test
    @DisplayName("上传：后缀不在白名单 → 拒绝（即使内容是合法图片）")
    void upload_unsupportedExtension() {
        assertThrows(BusinessException.class, () -> coverService.upload(file("cover.exe", PNG)));
        assertThrows(BusinessException.class, () -> coverService.upload(file("cover", PNG)));

        verifyNoInteractions(ossService);
    }

    @Test
    @DisplayName("上传：后缀大小写不敏感（.PNG 应当放行）")
    void upload_extensionIsCaseInsensitive() {
        when(ossService.upload(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn("https://cdn.example.com/c.png");

        assertEquals("https://cdn.example.com/c.png", coverService.upload(file("cover.PNG", PNG)));
    }

    // ---------- 用户上传：第二层 文件头魔数 ----------

    @Test
    @DisplayName("上传：把脚本/可执行文件改名成 .png → 必须拒绝（只信后缀就是把校验交给上传者）")
    void upload_renamedNonImage_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> coverService.upload(file("innocent.png", PLAIN_TEXT)));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verifyNoInteractions(ossService);
    }

    @Test
    @DisplayName("上传：内容短到连文件头都不够 → 拒绝而不是数组越界")
    void upload_tooShortToDetect() {
        assertThrows(BusinessException.class, () -> coverService.upload(file("a.png", new byte[] {1, 2})));
        assertThrows(BusinessException.class, () -> coverService.upload(file("a.png", new byte[] {1})));

        verifyNoInteractions(ossService);
    }

    @Test
    @DisplayName("上传：真实 PNG → 存成 png / image/png")
    void upload_png() {
        when(ossService.upload(any(byte[].class), anyString(), anyString(), anyString())).thenReturn("u");

        coverService.upload(file("a.png", PNG));

        verify(ossService).upload(any(byte[].class), eq(COVER_DIR), eq("png"), eq("image/png"));
    }

    @Test
    @DisplayName("上传：JPEG 改名成 .png → 按**真实类型** jpeg 存（否则下游按后缀解析会出错图）")
    void upload_renamedJpeg_storedByMagicBytes() {
        when(ossService.upload(any(byte[].class), anyString(), anyString(), anyString())).thenReturn("u");

        coverService.upload(file("a.png", JPEG));

        verify(ossService).upload(any(byte[].class), eq(COVER_DIR), eq("jpeg"), eq("image/jpeg"));
    }

    @Test
    @DisplayName("上传：GIF → gif / image/gif")
    void upload_gif() {
        when(ossService.upload(any(byte[].class), anyString(), anyString(), anyString())).thenReturn("u");

        coverService.upload(file("a.gif", GIF));

        verify(ossService).upload(any(byte[].class), eq(COVER_DIR), eq("gif"), eq("image/gif"));
    }

    @Test
    @DisplayName("上传：WEBP 靠 RIFF 容器的第 8-11 字节识别（偏移写错就会认不出来）")
    void upload_webp() {
        when(ossService.upload(any(byte[].class), anyString(), anyString(), anyString())).thenReturn("u");

        coverService.upload(file("a.png", WEBP));

        verify(ossService).upload(any(byte[].class), eq(COVER_DIR), eq("webp"), eq("image/webp"));
    }

    @Test
    @DisplayName("上传：只有 RIFF 头、没有 WEBP 标识 → 拒绝（RIFF 也用于 wav/avi）")
    void upload_riffWithoutWebpMark_rejected() {
        byte[] riffWave = {0x52, 0x49, 0x46, 0x46, 0x24, 0, 0, 0, 0x57, 0x41, 0x56, 0x45};

        assertThrows(BusinessException.class, () -> coverService.upload(file("a.png", riffWave)));

        verifyNoInteractions(ossService);
    }

    @Test
    @DisplayName("上传：转存失败时不吞掉根因（调用方只看到系统错误）")
    void upload_ossFailure_propagates() {
        when(ossService.upload(any(byte[].class), anyString(), anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "封面服务暂不可用，请稍后再试"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coverService.upload(file("a.png", PNG)));

        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("上传成功路径不会去占用文生图额度（两个入口的额度是分开的）")
    void upload_doesNotTouchQuota() {
        when(ossService.upload(any(byte[].class), anyString(), anyString(), anyString())).thenReturn("u");

        coverService.upload(file("a.png", PNG));

        verify(dailyQuotaLimiter, never()).tryAcquire(anyString(), anyLong(), anyLong());
        verifyNoInteractions(dashScopeClient);
    }
}
