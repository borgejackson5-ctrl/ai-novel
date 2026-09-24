package com.ainovel.module.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.PasswordUtil;
import com.ainovel.module.auth.domain.form.LoginForm;
import com.ainovel.module.auth.domain.form.ResetPasswordForm;
import com.ainovel.module.auth.service.impl.LoginServiceImpl;
import com.ainovel.module.user.dao.UserMapper;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录/注册服务单测：忘记密码重置（验码 → 查邮箱归属 → 重置密码）
 */
@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserService userService;
    @Mock
    private VerifyCodeService verifyCodeService;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private LoginService loginService;

    @BeforeEach
    void setUpRedis() {
        loginService = new LoginServiceImpl(userMapper, userService, verifyCodeService, stringRedisTemplate);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private ResetPasswordForm form() {
        ResetPasswordForm f = new ResetPasswordForm();
        f.setEmail("a@b.com");
        f.setCode("123456");
        f.setNewPassword("newPass456");
        return f;
    }

    @Test
    @DisplayName("重置密码成功 → 验码 + 查邮箱 + 重置密码 + 返回 userId")
    void resetPassword_success() {
        User user = new User();
        user.setId(1L);
        when(userMapper.selectOne(any())).thenReturn(user);

        Long id = loginService.resetPassword(form());

        assertEquals(1L, id);
        verify(verifyCodeService).verify(VerifyCodeService.SCENE_RESET, "a@b.com", "123456");
        verify(userService).updatePassword(1L, "newPass456");
    }

    @Test
    @DisplayName("重置密码邮箱未注册 → 抛 EMAIL_NOT_REGISTERED，不更新")
    void resetPassword_emailNotFound_throws() {
        when(userMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> loginService.resetPassword(form()));
        assertEquals(ErrorCode.EMAIL_NOT_REGISTERED, ex.getErrorCode());
        verify(userService, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("重置密码验证码错误 → 抛 CODE_ERROR，不查库不更新")
    void resetPassword_codeError_throws() {
        doThrow(new BusinessException(ErrorCode.CODE_ERROR)).when(verifyCodeService)
                .verify(anyString(), anyString(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> loginService.resetPassword(form()));
        assertEquals(ErrorCode.CODE_ERROR, ex.getErrorCode());
        verify(userMapper, never()).selectOne(any());
        verify(userService, never()).updatePassword(anyLong(), anyString());
    }

    private LoginForm loginForm(boolean rememberMe) {
        LoginForm f = new LoginForm();
        f.setIdentifier("admin");
        f.setPassword("admin123");
        f.setRememberMe(rememberMe);
        return f;
    }

    @Test
    @DisplayName("登录勾选记住我 → token 有效期 15 天")
    void login_rememberMe_15days() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
             MockedStatic<PasswordUtil> pwd = mockStatic(PasswordUtil.class)) {
            User user = new User();
            user.setId(1L);
            user.setPassword("hashed");
            when(userMapper.selectOne(any())).thenReturn(user);
            pwd.when(() -> PasswordUtil.matches(anyString(), anyString())).thenReturn(true);
            stp.when(() -> StpUtil.getTokenValue()).thenReturn("token123");
            when(userService.getUserRoleCode(1L)).thenReturn("user");

            loginService.login(loginForm(true));

            ArgumentCaptor<SaLoginParameter> captor = ArgumentCaptor.forClass(SaLoginParameter.class);
            stp.verify(() -> StpUtil.login(eq(1L), captor.capture()));
            assertEquals(15 * 24 * 3600L, captor.getValue().getTimeout());
        }
    }

    @Test
    @DisplayName("登录不勾选记住我 → token 有效期 1 天")
    void login_default_1day() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
             MockedStatic<PasswordUtil> pwd = mockStatic(PasswordUtil.class)) {
            User user = new User();
            user.setId(1L);
            user.setPassword("hashed");
            when(userMapper.selectOne(any())).thenReturn(user);
            pwd.when(() -> PasswordUtil.matches(anyString(), anyString())).thenReturn(true);
            stp.when(() -> StpUtil.getTokenValue()).thenReturn("token123");
            when(userService.getUserRoleCode(1L)).thenReturn("user");

            loginService.login(loginForm(false));

            ArgumentCaptor<SaLoginParameter> captor = ArgumentCaptor.forClass(SaLoginParameter.class);
            stp.verify(() -> StpUtil.login(eq(1L), captor.capture()));
            assertEquals(24 * 3600L, captor.getValue().getTimeout());
        }
    }
}
