package com.ainovel.module.auth.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 登录返回
 */
@Data
@Builder
public class LoginVO {

    private String token;
    private Long userId;
    private String username;
    private String nickname;
    private Integer coinBalance;

    /** 角色编码：admin / user（前端据此切换界面与入口） */
    private String role;
}
