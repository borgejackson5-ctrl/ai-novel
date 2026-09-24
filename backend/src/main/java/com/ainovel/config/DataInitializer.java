package com.ainovel.config;

import com.ainovel.common.constant.RoleConstant;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.util.PasswordUtil;
import com.ainovel.module.user.dao.UserMapper;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.domain.entity.UserRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化管理员 / 测试账号（用户表为空时执行）
 *
 * <p>生产环境默认不执行：{@code app.seed-demo-accounts} 在 application-prod.yaml 中为 false。
 * 这两个账号的初始口令公开写在 README 中（admin123 / user123），
 * 若在用户表为空的公网库上自动创建，相当于保留一个口令已知的管理员账号。
 *
 * <p>演示站需要登录时，通过环境变量启用并修改口令：
 * {@code SEED_DEMO_ACCOUNTS=true SEED_ADMIN_PASSWORD=<强口令> SEED_USER_PASSWORD=<强口令>}。
 *
 * <p>账号口令统一经 BCrypt 加密后持久化，且禁止写入日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;

    private final UserRoleMapper userRoleMapper;

    /** 是否创建演示账号（本地默认开、生产默认关） */
    @Value("${app.seed-demo-accounts:true}")
    private boolean seedDemoAccounts;

    @Value("${app.seed-admin-password:admin123}")
    private String adminPassword;

    @Value("${app.seed-user-password:user123}")
    private String userPassword;

    @Override
    public void run(String... args) {
        if (!seedDemoAccounts) {
            log.info("演示账号初始化已关闭（app.seed-demo-accounts=false），跳过");
            return;
        }

        Long count = userMapper.selectCount(new LambdaQueryWrapper<>());
        if (count != null && count > 0) {
            return;
        }

        // 管理员（初始口令由 app.seed-admin-password 提供）
        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(PasswordUtil.encode(adminPassword));
        admin.setNickname("超级管理员");
        admin.setCoinBalance(10000);
        admin.setStatus(CommonStatusEnum.ENABLED.getCode());
        userMapper.insert(admin);
        userRoleMapper.insert(buildUserRole(admin.getId(), RoleConstant.ADMIN_ROLE_ID));

        // 测试用户（初始口令由 app.seed-user-password 提供）
        User user = new User();
        user.setUsername("user");
        user.setPassword(PasswordUtil.encode(userPassword));
        user.setNickname("测试用户");
        user.setCoinBalance(100);
        user.setStatus(CommonStatusEnum.ENABLED.getCode());
        userMapper.insert(user);
        userRoleMapper.insert(buildUserRole(user.getId(), RoleConstant.USER_ROLE_ID));

        // 注意：不在日志中输出任何明文密码
        log.info("初始化默认账号完成: admin(管理员), user(普通用户)，初始口令见 app.seed-*-password 配置");
    }

    private UserRole buildUserRole(Long userId, long roleId) {
        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        return userRole;
    }
}
