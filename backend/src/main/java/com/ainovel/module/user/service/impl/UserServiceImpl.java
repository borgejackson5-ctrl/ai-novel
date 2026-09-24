package com.ainovel.module.user.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.RoleConstant;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.PasswordUtil;
import com.ainovel.module.user.dao.RoleMapper;
import com.ainovel.module.user.dao.UserMapper;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.domain.entity.UserRole;
import com.ainovel.module.user.domain.vo.UserVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.ainovel.module.user.service.UserService;

/**
 * 用户服务
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    private final UserRoleMapper userRoleMapper;

    private final RoleMapper roleMapper;

    /** 受保护（演示）账号白名单（逗号分隔），这些账号的密码/昵称不可修改 */
    @Value("${app.protected-accounts:admin,user}")
    private String protectedAccounts;

    public UserVO getUserVO(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        UserVO vo = BeanUtil.copyProperties(user, UserVO.class);
        vo.setRole(getUserRoleCode(userId));
        return vo;
    }

    /**
     * 查询用户角色编码：含 admin 角色返回 admin，否则返回 user。
     *
     * @return 角色编码；用户没有任何角色时返回 null
     */
    public String getUserRoleCode(Long userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return null;
        }
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        boolean admin = roleMapper.selectBatchIds(roleIds).stream()
                .anyMatch(r -> RoleConstant.ROLE_CODE_ADMIN.equals(r.getRoleCode()));
        return admin ? RoleConstant.ROLE_CODE_ADMIN : RoleConstant.ROLE_CODE_USER;
    }

    public User getUser(Long userId) {
        return userId == null ? null : userMapper.selectById(userId);
    }

    public List<User> listUsers(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userMapper.selectBatchIds(userIds);
    }

    public Map<Long, String> getNicknameMap(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        u -> StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername(),
                        (a, b) -> a));
    }

    public boolean deductCoin(Long userId, int amount) {
        // 原子 SQL：coin_balance >= amount 与 is_deleted = 0 都在 WHERE 里，并发下不会超扣
        return userMapper.deductCoin(userId, amount) > 0;
    }

    public boolean addCoin(Long userId, int amount) {
        return userMapper.addCoin(userId, amount) > 0;
    }

    /**
     * 创建用户（注册 / 邮箱转注册，带邮箱；手机号预留）。
     *
     * <p>用户名唯一由 t_user.uk_username 兜底，并发下重复注册会抛
     * {@link DuplicateKeyException}（事务自动回滚），由调用方决定提示还是降级。
     * 两条调用路径（{@code LoginServiceImpl.register} 与
     * {@code LoginServiceImpl.registerByEmailLogin}）都是捕获它并统一提示「账号已存在」。
     *
     * <p>两参重载 {@code createUser(username, password)} 已移除：该路径会绕过邮箱验证码，
     * 已在 {@code LoginServiceImpl} 中取消，重载因此没有调用方，其 javadoc 描述的旧行为也不再成立。
     * UserService 接口与本类均仅保留这一个带邮箱的重载。
     */
    @Transactional(rollbackFor = Exception.class)
    public User createUser(String username, String rawPassword, String email, String phone) {
        return doCreate(username, rawPassword, email, phone);
    }

    private User doCreate(String username, String rawPassword, String email, String phone) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(PasswordUtil.encode(rawPassword));
        user.setNickname(username);
        user.setEmail(email);
        user.setPhone(phone);
        user.setCoinBalance(0);
        user.setStatus(CommonStatusEnum.ENABLED.getCode());
        userMapper.insert(user);

        UserRole userRole = new UserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(RoleConstant.USER_ROLE_ID);
        userRoleMapper.insert(userRole);
        return user;
    }

    /**
     * 是否受保护账号（演示号/管理员，密码与账号信息不可改）。
     */
    private boolean isProtectedAccount(String username) {
        if (username == null || protectedAccounts == null || protectedAccounts.isBlank()) {
            return false;
        }
        return Arrays.stream(protectedAccounts.split(","))
                .map(String::trim)
                .anyMatch(username::equals);
    }

    /**
     * 受保护账号不允许修改密码/昵称：命中则抛 DEMO_ACCOUNT_LOCKED。
     */
    public void assertNotProtected(User user) {
        if (user != null && isProtectedAccount(user.getUsername())) {
            throw new BusinessException(ErrorCode.DEMO_ACCOUNT_LOCKED);
        }
    }

    /**
     * 修改昵称/笔名（登录后）。昵称非唯一，直接覆盖（MyBatis-Plus NOT_NULL 只更新非空字段）。
     */
    public void updateNickname(Long userId, String nickname) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        assertNotProtected(user);
        User update = new User();
        update.setId(userId);
        update.setNickname(nickname);
        userMapper.updateById(update);
    }

    /**
     * 修改密码（登录后）：校验原密码通过后 BCrypt 更新新密码。
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        assertNotProtected(user);
        if (!PasswordUtil.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.OLD_PASSWORD_ERROR);
        }
        updatePassword(userId, newPassword);
    }

    /**
     * 按主键重置/更新密码（已通过原密码或邮箱验证码校验）：BCrypt 加密后仅更新密码列。
     */
    public void updatePassword(Long userId, String rawPassword) {
        User user = new User();
        user.setId(userId);
        user.setPassword(PasswordUtil.encode(rawPassword));
        userMapper.updateById(user);
    }
}
