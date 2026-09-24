package com.ainovel.module.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.ainovel.module.user.dao.RoleMapper;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.ainovel.module.user.domain.entity.Role;
import com.ainovel.module.user.domain.entity.UserRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sa-Token 权限数据源：从 DB 加载用户角色与权限
 *
 * <p>角色列表使用 Caffeine 本地缓存，写入后 5 分钟过期，避免每次鉴权查询两次 DB。
 * 缓存为进程内缓存，多实例部署时各实例互不可见，{@link #evictRoleCache} 只能失效当前实例，
 * 届时应改用共享缓存或广播失效。
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserRoleMapper userRoleMapper;

    private final RoleMapper roleMapper;

    private final Cache<Long, List<String>> roleCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        List<String> roles = getRoleList(loginId, loginType);
        // admin 拥有全部权限
        if (roles.contains("admin")) {
            List<String> all = new ArrayList<>();
            all.add("*");
            return all;
        }
        // 普通用户基础权限
        return List.of("novel:view", "novel:like", "rank:view", "subscribe:unlock", "ai:generate");
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        return roleCache.get(userId, this::loadRoles);
    }

    private List<String> loadRoles(Long userId) {
        List<UserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));
        if (userRoles.isEmpty()) {
            return List.of();
        }
        List<Long> roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        return roles.stream().map(Role::getRoleCode).toList();
    }

    /**
     * 使指定用户的角色缓存条目失效。
     *
     * <p>角色的写入路径必须调用本方法：缓存过期前旧角色仍然参与鉴权，
     * 撤销管理员后该用户在这段时间内仍可访问管理端接口。
     * 当前角色仅在注册（{@code UserServiceImpl}）与启动初始化（{@code DataInitializer}）时写入，
     * 尚无修改已有用户角色的入口，因此暂无调用方；新增改角色入口时须同步调用。
     */
    public void evictRoleCache(Long userId) {
        roleCache.invalidate(userId);
    }
}
