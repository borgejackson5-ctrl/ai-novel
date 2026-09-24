package com.ainovel.module.user.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户信息视图（不含密码）
 */
@Data
public class UserVO {

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private Integer coinBalance;
    private Integer status;

    /** 角色编码：admin / user（管理列表展示用） */
    private String role;

    /** 注册时间（管理列表展示用） */
    private LocalDateTime createTime;
}
