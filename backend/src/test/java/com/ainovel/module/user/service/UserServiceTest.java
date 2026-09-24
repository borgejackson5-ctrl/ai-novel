package com.ainovel.module.user.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.PasswordUtil;
import com.ainovel.module.user.dao.RoleMapper;
import com.ainovel.module.user.dao.UserMapper;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户服务单测：改密码（原密码校验 + 加密更新）、改昵称、重置密码（按主键局部更新）。
 *
 * <p>受保护账号的设置统一放在 {@link BeforeEach}：{@code @Value} 在没有 Spring 上下文时不会注入，
 * 字段会停在 {@code null}，而 {@code isProtectedAccount} 对 null 名单直接返回 false，
 * 结果是受保护账号的校验在绝大多数用例中被静默跳过：把用例中的用户名换成 admin 也照样通过，
 * 与线上行为不一致，等于未测试。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    /** 与 {@code @Value("${app.protected-accounts:admin,user}")} 的默认值保持一致 */
    private static final String DEFAULT_PROTECTED = "admin,user";

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserRoleMapper userRoleMapper;
    @Mock
    private RoleMapper roleMapper;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, userRoleMapper, roleMapper);
        ReflectionTestUtils.setField(userService, "protectedAccounts", DEFAULT_PROTECTED);
    }

    @Test
    @DisplayName("改密码成功 → 校验原密码通过后按主键更新加密新密码（非受保护账号）")
    void changePassword_success() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword(PasswordUtil.encode("oldPass123"));
        when(userMapper.selectById(1L)).thenReturn(user);

        userService.changePassword(1L, "oldPass123", "newPass456");

        verify(userMapper).updateById(argThat((User u) ->
                u.getId().equals(1L) && u.getPassword() != null && !"newPass456".equals(u.getPassword())));
    }

    @Test
    @DisplayName("改密码原密码错误 → 抛 OLD_PASSWORD_ERROR，不更新")
    void changePassword_wrongOld_throws() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword(PasswordUtil.encode("oldPass123"));
        when(userMapper.selectById(1L)).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(1L, "wrong", "newPass456"));
        assertEquals(ErrorCode.OLD_PASSWORD_ERROR, ex.getErrorCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("改密码用户不存在 → 抛 NOT_FOUND，不更新")
    void changePassword_userNotFound_throws() {
        when(userMapper.selectById(1L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(1L, "x", "newPass456"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("改昵称 → 按主键局部更新昵称")
    void updateNickname_success() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        when(userMapper.selectById(1L)).thenReturn(user);

        userService.updateNickname(1L, "小明");

        verify(userMapper).updateById(argThat((User u) ->
                u.getId().equals(1L) && "小明".equals(u.getNickname())));
    }

    @Test
    @DisplayName("改密码：受保护账号（admin）→ 抛 DEMO_ACCOUNT_LOCKED，不更新")
    void changePassword_protectedAccount_throws() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        when(userMapper.selectById(1L)).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(1L, "old", "new"));
        assertEquals(ErrorCode.DEMO_ACCOUNT_LOCKED, ex.getErrorCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("改昵称：受保护账号（user）→ 抛 DEMO_ACCOUNT_LOCKED，不更新")
    void updateNickname_protectedAccount_throws() {
        User user = new User();
        user.setId(2L);
        user.setUsername("user");
        when(userMapper.selectById(2L)).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateNickname(2L, "黑客"));
        assertEquals(ErrorCode.DEMO_ACCOUNT_LOCKED, ex.getErrorCode());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("受保护账号名单：配置写成「admin, user」（带空格）也不能失效")
    void protectedAccounts_toleratesSpaces() {
        ReflectionTestUtils.setField(userService, "protectedAccounts", " admin , user ");
        User user = new User();
        user.setId(1L);
        user.setUsername("user");
        when(userMapper.selectById(1L)).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateNickname(1L, "黑客"));

        assertEquals(ErrorCode.DEMO_ACCOUNT_LOCKED, ex.getErrorCode(),
                "名单里带空格就没匹配上，等于这道保护形同不存在");
    }

    @Test
    @DisplayName("受保护账号名单为空 → 不做拦截（配置成空字符串时的兜底语义）")
    void protectedAccounts_blankMeansNoLock() {
        ReflectionTestUtils.setField(userService, "protectedAccounts", "");
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        when(userMapper.selectById(1L)).thenReturn(user);

        userService.updateNickname(1L, "新名字");

        verify(userMapper).updateById(any(User.class));
    }

    @Test
    @DisplayName("受保护账号默认名单：配置 key 与默认值都要守住（部署不传这个 key 时靠它）")
    void protectedAccounts_defaultValue() throws Exception {
        Field field = UserServiceImpl.class.getDeclaredField("protectedAccounts");
        Value value = field.getAnnotation(Value.class);

        assertNotNull(value, "受保护账号名单上没有 @Value —— 部署时无法用环境变量覆盖");
        String expr = value.value();
        assertTrue(expr.startsWith("${app.protected-accounts:"),
                "配置 key 变了，部署时按老 key 配的值会静默失效。实际：" + expr);

        String defaults = expr.substring(expr.indexOf(':') + 1, expr.length() - 1);
        assertEquals(DEFAULT_PROTECTED, defaults,
                "默认名单变了：没配这个 key 的环境（例如容器里漏传环境变量）会跟着一起变" +
                        "——名单一旦被清空，演示号与管理员就能被改名改密码");
    }

    @Test
    @DisplayName("重置密码 → BCrypt 加密后按主键局部更新")
    void updatePassword_success() {
        userService.updatePassword(1L, "newPass456");

        verify(userMapper).updateById(argThat((User u) ->
                u.getId().equals(1L) && u.getPassword() != null && !"newPass456".equals(u.getPassword())));
    }

    @Test
    @DisplayName("扣币 → 直接反映 SQL 影响行数（0 行即失败，原因由调用方解释）")
    void deductCoin_reflectsAffectedRows() {
        when(userMapper.deductCoin(1L, 100)).thenReturn(1);
        assertTrue(userService.deductCoin(1L, 100), "影响 1 行应返回 true");

        // 影响 0 行的两种情况（余额不足 / 用户已注销）在 SQL 的 WHERE 里混在一起，
        // 这里只返回 false：区分具体原因是调用方的职责
        when(userMapper.deductCoin(1L, 999_999)).thenReturn(0);
        assertFalse(userService.deductCoin(1L, 999_999), "影响 0 行应返回 false");
    }

    @Test
    @DisplayName("加币 → 同上；用户不存在时返回 false")
    void addCoin_reflectsAffectedRows() {
        when(userMapper.addCoin(1L, 100)).thenReturn(1);
        assertTrue(userService.addCoin(1L, 100), "影响 1 行应返回 true");

        when(userMapper.addCoin(9L, 100)).thenReturn(0);
        assertFalse(userService.addCoin(9L, 100), "影响 0 行应返回 false");
    }
}
