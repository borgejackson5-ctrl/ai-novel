package com.ainovel.config;

import com.ainovel.common.util.PasswordUtil;
import com.ainovel.module.user.dao.UserMapper;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.domain.entity.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 演示账号初始化的开关测试。
 *
 * <p>背景：{@code DataInitializer} 会在「用户表为空」时创建 {@code admin/admin123}、
 * {@code user/user123}，而这两个口令公开写在 README 中。开关由 {@code app.seed-demo-accounts}
 * 控制，{@code application-prod.yaml} 中默认为 false；关闭后即使生产库为空，
 * 也不会留下一个口令已知的管理员账号。
 */
@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserRoleMapper userRoleMapper;

    private DataInitializer dataInitializer;

    @BeforeEach
    void initService() {
        dataInitializer = new DataInitializer(userMapper, userRoleMapper);
    }

    @Test
    @DisplayName("开关关闭（生产默认）→ 完全不碰数据库，一个账号都不建")
    void seedDisabled_doesNothing() {
        ReflectionTestUtils.setField(dataInitializer, "seedDemoAccounts", false);

        dataInitializer.run();

        verifyNoInteractions(userMapper);
        verifyNoInteractions(userRoleMapper);
    }

    @Test
    @DisplayName("开关打开 + 用户表为空 → 建 admin/user 两个账号，口令用配置值且加密入库")
    void seedEnabled_emptyTable_createsAccounts() {
        ReflectionTestUtils.setField(dataInitializer, "seedDemoAccounts", true);
        ReflectionTestUtils.setField(dataInitializer, "adminPassword", "s3cret-admin-pw");
        ReflectionTestUtils.setField(dataInitializer, "userPassword", "s3cret-user-pw");
        when(userMapper.selectCount(any())).thenReturn(0L);
        // insert 要回填 id（后面建 user_role 依赖它）
        when(userMapper.insert(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId("admin".equals(u.getUsername()) ? 1L : 2L);
            return 1;
        });

        dataInitializer.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper, times(2)).insert(captor.capture());
        List<User> created = captor.getAllValues();
        assertEquals(List.of("admin", "user"), created.stream().map(User::getUsername).toList());

        for (User u : created) {
            assertFalse("admin123".equals(u.getPassword()) || "user123".equals(u.getPassword()),
                    "口令不能是明文，必须是 BCrypt 密文");
            assertTrue(u.getPassword().startsWith("$2"), "BCrypt 密文应以 $2 开头，实际=" + u.getPassword());
        }
        // 配置里给的口令必须真的生效（生产用演示账号时靠它改掉默认口令）
        assertTrue(PasswordUtil.matches("s3cret-admin-pw", created.get(0).getPassword()),
                "admin 的口令应来自 app.seed-admin-password");
        assertTrue(PasswordUtil.matches("s3cret-user-pw", created.get(1).getPassword()),
                "user 的口令应来自 app.seed-user-password");

        verify(userRoleMapper, times(2)).insert(any(UserRole.class));
    }

    @Test
    @DisplayName("开关打开但库里已有用户 → 不重复创建（幂等）")
    void seedEnabled_nonEmptyTable_skips() {
        ReflectionTestUtils.setField(dataInitializer, "seedDemoAccounts", true);
        when(userMapper.selectCount(any())).thenReturn(5L);

        dataInitializer.run();

        verify(userMapper, never()).insert(any(User.class));
        verifyNoInteractions(userRoleMapper);
    }
}
