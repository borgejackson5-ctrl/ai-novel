package com.ainovel.module.auth.service;

import com.ainovel.module.auth.domain.form.LoginForm;
import com.ainovel.module.auth.domain.form.RegisterForm;
import com.ainovel.module.auth.domain.form.ResetPasswordForm;
import com.ainovel.module.auth.domain.vo.LoginVO;

/**
 * 登录/注册服务（含防爆破：同一账号 10 分钟内最多失败 5 次）
 *
 * <p>登录标识符：用户名或邮箱（按是否含 @ 自动识别）。
 * 注册策略：
 * <ul>
 *   <li>用户名登录未注册 → 自动注册（用户名非联系方式，宽松准入，无需验证码）。</li>
 *   <li>邮箱登录未注册 → 需邮箱验证码，验证归属后转注册（避免冒用他人邮箱）。</li>
 *   <li>显式注册 → 校验邮箱验证码 + 用户名/邮箱唯一。</li>
 * </ul>
 */
public interface LoginService {

    public LoginVO login(LoginForm form);

    /**
     * 显式注册：校验邮箱验证码 + 用户名/邮箱唯一；成功后直接登录。
     */
    public LoginVO register(RegisterForm form);

    /**
     * 忘记密码重置：校验邮箱验证码后重置密码（不自动登录，需重新登录）。
     *
     * <p>先校验并消费一次性验证码，再查邮箱归属，避免无码探测注册状态。
     *
     * @return 用户 ID，供调用方踢下线（让该账号所有残留会话失效）
     */
    public Long resetPassword(ResetPasswordForm form);
}
