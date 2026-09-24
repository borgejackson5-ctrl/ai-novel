package com.ainovel.module.user.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.user.domain.form.ChangePasswordForm;
import com.ainovel.module.user.domain.form.UpdateNicknameForm;
import com.ainovel.module.user.domain.vo.UserVO;
import com.ainovel.module.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户接口
 */
@Tag(name = "用户")
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "当前用户信息")
    @GetMapping("/me")
    public ResponseDTO<UserVO> me() {
        return ResponseDTO.ok(userService.getUserVO(LoginUserUtil.getUserId()));
    }

    @Operation(summary = "修改密码（登录后，需原密码）")
    @PutMapping("/password")
    public ResponseDTO<Void> changePassword(@Valid @RequestBody ChangePasswordForm form) {
        Long userId = LoginUserUtil.getUserId();
        userService.changePassword(userId, form.getOldPassword(), form.getNewPassword());
        // 改密后踢下线该账号所有会话（含当前），强制重新登录
        StpUtil.logout(userId);
        return ResponseDTO.ok();
    }

    @Operation(summary = "修改昵称/笔名")
    @PutMapping("/nickname")
    public ResponseDTO<Void> updateNickname(@Valid @RequestBody UpdateNicknameForm form) {
        userService.updateNickname(LoginUserUtil.getUserId(), form.getNickname().trim());
        return ResponseDTO.ok();
    }
}
