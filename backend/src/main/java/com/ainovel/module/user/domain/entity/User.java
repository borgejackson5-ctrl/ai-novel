package com.ainovel.module.user.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user")
public class User extends BaseEntity {

    private String username;
    private String password;
    private String nickname;
    private String avatar;
    /** 邮箱（登录方式之一，唯一） */
    private String email;
    /** 手机号（预留字段，暂不参与登录/校验） */
    private String phone;
    private Integer coinBalance;
    private Integer status;
}
