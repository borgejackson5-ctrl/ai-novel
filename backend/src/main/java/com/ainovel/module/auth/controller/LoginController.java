package com.ainovel.module.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.module.auth.domain.form.CodeForm;
import com.ainovel.module.auth.domain.form.LoginForm;
import com.ainovel.module.auth.domain.form.RegisterForm;
import com.ainovel.module.auth.domain.form.ResetPasswordForm;
import com.ainovel.module.auth.domain.vo.LoginVO;
import com.ainovel.module.auth.service.LoginService;
import com.ainovel.module.auth.service.VerifyCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ainovel.common.ratelimit.RateLimit;

/**
 * 认证接口
 */
@Tag(name = "认证")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class LoginController {

    private final LoginService loginService;

    private final VerifyCodeService verifyCodeService;

    @Operation(summary = "发送邮箱验证码（scene: register / login / reset）")
    @PostMapping("/code")
    @RateLimit(name = "auth:code", limit = 5, window = 300)
    public ResponseDTO<Void> sendCode(@Valid @RequestBody CodeForm form) {
        verifyCodeService.send(form.getScene(), form.getEmail());
        return ResponseDTO.ok();
    }

    @Operation(summary = "登录（用户名或邮箱 + 密码；邮箱未注册需先取验证码转注册）")
    @PostMapping("/login")
    // 30 次/分钟按客户端 IP 统计：该阈值面向撞库机器人而非单个真实用户
    // （单用户一分钟内不会登录 30 次），实际的失败保护在 service 层（连续 5 次失败锁 10 分钟）。
    // 阈值调低会误伤两类正常场景：同一出口 IP 下的多人（办公室 / 校园网），以及
    // 「登录 → 401 → 重试」的多标签页。集成测试中每个用例均需登录一次，10 次也不足用。
    @RateLimit(name = "auth:login", limit = 30)
    public ResponseDTO<LoginVO> login(@Valid @RequestBody LoginForm form) {
        return ResponseDTO.ok(loginService.login(form));
    }

    @Operation(summary = "注册（邮箱 + 用户名 + 密码 + 邮箱验证码）")
    @PostMapping("/register")
    @RateLimit(name = "auth:register", limit = 5, window = 300)
    public ResponseDTO<LoginVO> register(@Valid @RequestBody RegisterForm form) {
        return ResponseDTO.ok(loginService.register(form));
    }

    @Operation(summary = "忘记密码重置（邮箱 + 验证码 + 新密码）")
    @PostMapping("/reset-password")
    @RateLimit(name = "auth:reset", limit = 5, window = 300)
    public ResponseDTO<Void> resetPassword(@Valid @RequestBody ResetPasswordForm form) {
        Long userId = loginService.resetPassword(form);
        // 重置后踢下线该账号所有会话，强制重新登录
        StpUtil.logout(userId);
        return ResponseDTO.ok();
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public ResponseDTO<Void> logout() {
        StpUtil.logout();
        return ResponseDTO.ok();
    }
}
