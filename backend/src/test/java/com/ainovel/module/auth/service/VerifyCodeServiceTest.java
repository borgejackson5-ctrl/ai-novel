package com.ainovel.module.auth.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.auth.sender.CodeSender;
import com.ainovel.module.auth.service.impl.VerifyCodeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 验证码服务单测：覆盖防爆破（尝试上限作废）与发送失败清理（幽灵码）。
 */
@ExtendWith(MockitoExtension.class)
class VerifyCodeServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CodeSender codeSender;

    private VerifyCodeService verifyCodeService;

    @BeforeEach
    void setUp() {
        verifyCodeService = new VerifyCodeServiceImpl(stringRedisTemplate, codeSender);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("校验失败累计达 5 次 → 作废验证码并抛过期")
    void verify_tooManyAttempts_invalidate() {
        when(valueOperations.get(anyString())).thenReturn("123456");
        when(valueOperations.increment(anyString())).thenReturn(5L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifyCodeService.verify(VerifyCodeService.SCENE_REGISTER, "a@b.com", "000000"));

        assertEquals(ErrorCode.CODE_EXPIRED, ex.getErrorCode());
        // 作废：删码 + 删尝试计数
        verify(stringRedisTemplate, atLeast(2)).delete(anyString());
    }

    @Test
    @DisplayName("校验失败未达上限 → 抛 CODE_ERROR")
    void verify_wrongCode_throwsCodeError() {
        when(valueOperations.get(anyString())).thenReturn("123456");
        when(valueOperations.increment(anyString())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifyCodeService.verify(VerifyCodeService.SCENE_REGISTER, "a@b.com", "000000"));

        assertEquals(ErrorCode.CODE_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("校验正确 → 删码并删尝试计数")
    void verify_correct_delete() {
        when(valueOperations.get(anyString())).thenReturn("123456");

        verifyCodeService.verify(VerifyCodeService.SCENE_REGISTER, "a@b.com", "123456");

        verify(stringRedisTemplate, times(2)).delete(anyString());
    }

    @Test
    @DisplayName("发送失败 → 清理码与限频 key，抛友好错误（不留幽灵码）")
    void send_smtpFail_cleanup() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        doThrow(new RuntimeException("smtp down")).when(codeSender).send(anyString(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> verifyCodeService.send(VerifyCodeService.SCENE_REGISTER, "a@b.com"));

        assertEquals(ErrorCode.SYSTEM_ERROR, ex.getErrorCode());
        verify(stringRedisTemplate, atLeast(2)).delete(anyString());
    }

    @Test
    @DisplayName("reset 场景（忘记密码）被允许 → 正常发码，不抛非法场景")
    void send_resetScene_allowed() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        verifyCodeService.send(VerifyCodeService.SCENE_RESET, "a@b.com");

        verify(codeSender).send(anyString(), anyString());
    }
}
