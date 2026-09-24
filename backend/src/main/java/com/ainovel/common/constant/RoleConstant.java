package com.ainovel.common.constant;

/**
 * 系统内置角色 ID（与 sql/init.sql 中 t_role 种子数据对应）
 */
public final class RoleConstant {

    /** 管理员角色 ID */
    public static final long ADMIN_ROLE_ID = 1L;

    /** 普通用户角色 ID */
    public static final long USER_ROLE_ID = 2L;

    /** 管理员角色编码（与 t_role.role_code 对应） */
    public static final String ROLE_CODE_ADMIN = "admin";

    /** 普通用户角色编码 */
    public static final String ROLE_CODE_USER = "user";

    private RoleConstant() {
    }
}
