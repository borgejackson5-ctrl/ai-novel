package com.ainovel.common.util;

import cn.dev33.satoken.stp.StpUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.RoleConstant;
import com.ainovel.common.exception.BusinessException;

/**
 * 当前登录用户工具
 */
public class LoginUserUtil {

    private LoginUserUtil() {
    }

    public static Long getUserId() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 尝试获取当前登录用户 ID，未登录返回 null（不抛出异常）。
     * 用于「可选登录」场景，如详情页回填当前用户的点赞状态（未登录默认为未点赞）。
     */
    public static Long getUserIdOrNull() {
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 当前登录用户是否为管理员。
     *
     * <p>依据 Sa-Token 角色（{@link com.ainovel.module.auth.StpInterfaceImpl} 从 t_role 加载），
     * 同一请求内的角色结果由 Sa-Token 缓存，无额外 DB 查询。
     * 无 Sa-Token 上下文（如单测）时返回 false，不误放行。
     */
    public static boolean isAdmin() {
        try {
            return StpUtil.hasRole(RoleConstant.ROLE_CODE_ADMIN);
        } catch (Exception e) {
            return false;
        }
    }
}
