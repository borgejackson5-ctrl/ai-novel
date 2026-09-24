package com.ainovel.module.auth.service.impl;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.IpUtil;
import com.ainovel.common.util.PasswordUtil;
import com.ainovel.module.auth.domain.form.LoginForm;
import com.ainovel.module.auth.domain.form.RegisterForm;
import com.ainovel.module.auth.domain.form.ResetPasswordForm;
import com.ainovel.module.auth.domain.vo.LoginVO;
import com.ainovel.module.user.dao.UserMapper;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.List;
import com.ainovel.module.auth.service.VerifyCodeService;
import com.ainovel.module.auth.service.LoginService;

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
@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {

    /** 登录失败计数 key 前缀（账号+IP 维度，缓解仅凭用户名即可锁定他人账号的 DoS） */
    private static final String LOGIN_FAIL_KEY = "login:fail:";

    /** 登录失败计数 key 前缀（纯 IP 维度，防单 IP 批量爆破） */
    private static final String LOGIN_FAIL_IP_KEY = "login:fail:ip:";

    /** 账号+IP 维度失败次数上限 */
    private static final int MAX_FAIL_COUNT = 5;

    /** 纯 IP 维度失败次数上限（阈值更高，防批量爆破） */
    private static final int MAX_IP_FAIL_COUNT = 50;

    /** 计数窗口 */
    private static final Duration FAIL_WINDOW = Duration.ofMinutes(10);

    /** 未勾选「记住我」：1 天绝对 + 12 小时不活跃（短会话，适合临时登录） */
    private static final long TIMEOUT_DEFAULT = 24 * 3600L;
    private static final long ACTIVE_TIMEOUT_DEFAULT = 12 * 3600L;

    /** 勾选「记住我」：15 天绝对 + 7 天不活跃（长会话） */
    private static final long TIMEOUT_REMEMBER = 15 * 24 * 3600L;
    private static final long ACTIVE_TIMEOUT_REMEMBER = 7 * 24 * 3600L;

    /** Lua：INCR + 首次 EXPIRE 原子化，避免极端下 EXPIRE 失败导致永久锁 */
    private static final String INCR_WITH_EXPIRE_LUA = """
            local c = redis.call('incr', KEYS[1])
            if c == 1 then
                redis.call('expire', KEYS[1], ARGV[1])
            end
            return c
            """;

    private final UserMapper userMapper;

    private final UserService userService;

    private final VerifyCodeService verifyCodeService;

    private final StringRedisTemplate stringRedisTemplate;

    public LoginVO login(LoginForm form) {
        String identifier = form.getIdentifier().trim();
        checkFailLimit(identifier);

        User user;
        if (identifier.contains("@")) {
            user = selectByEmail(identifier);
            if (user == null) {
                user = registerByEmailLogin(identifier, form);
            }
        } else {
            user = selectByUsername(identifier);
            if (user == null) {
                // 用户名未注册：不再自动创建账号。
                // 该路径绕过注册所需的邮箱验证码，等同于留下一个无验证的注册入口；
                // 且用户输错一个字符会静默创建一个空账号，误认为是自己的账号。
                // 提示沿用与密码错误相同的文案，不暴露「该用户名是否已存在」。
                recordFail(identifier);
                throw new BusinessException(ErrorCode.LOGIN_FAIL);
            }
        }

        if (user == null || !PasswordUtil.matches(form.getPassword(), user.getPassword())) {
            recordFail(identifier);
            throw new BusinessException(ErrorCode.LOGIN_FAIL);
        }
        if (user.getStatus() != null && user.getStatus() == CommonStatusEnum.DISABLED.getCode()) {
            throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        }

        // 登录成功，清空该「IP+账号」维度的失败计数
        stringRedisTemplate.delete(LOGIN_FAIL_KEY + IpUtil.getClientIp() + ":" + identifier);
        return doLogin(user, Boolean.TRUE.equals(form.getRememberMe()));
    }

    /**
     * 显式注册：校验邮箱验证码 + 用户名/邮箱唯一；成功后直接登录。
     */
    public LoginVO register(RegisterForm form) {
        String username = form.getUsername().trim();
        String email = form.getEmail().trim();
        checkFailLimit(username);

        // 统一模糊提示，避免精确区分「用户名/邮箱」被枚举（与登录路径一致）
        if (selectByUsername(username) != null || selectByEmail(email) != null) {
            throw new BusinessException(ErrorCode.ACCOUNT_EXISTS);
        }
        verifyWithFailRecord(email, VerifyCodeService.SCENE_REGISTER, form.getCode());

        User user;
        try {
            user = userService.createUser(username, form.getPassword(), email, null);
        } catch (DuplicateKeyException e) {
            // 并发兜底：唯一键冲突统一模糊提示
            throw new BusinessException(ErrorCode.ACCOUNT_EXISTS);
        }

        stringRedisTemplate.delete(LOGIN_FAIL_KEY + IpUtil.getClientIp() + ":" + username);
        return doLogin(user, false);
    }

    /**
     * 忘记密码重置：校验邮箱验证码后重置密码（不自动登录，需重新登录）。
     *
     * <p>先校验并消费一次性验证码，再查邮箱归属，避免无码探测注册状态。
     *
     * @return 用户 ID，供调用方踢下线（让该账号所有残留会话失效）
     */
    public Long resetPassword(ResetPasswordForm form) {
        String email = form.getEmail().trim();
        verifyCodeService.verify(VerifyCodeService.SCENE_RESET, email, form.getCode());
        User user = selectByEmail(email);
        if (user == null) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_REGISTERED);
        }
        userService.assertNotProtected(user);
        userService.updatePassword(user.getId(), form.getNewPassword());
        return user.getId();
    }

    /**
     * 邮箱登录未注册 → 验证码转注册（用户名自动取邮箱前缀，冲突加数字后缀）。
     */
    private User registerByEmailLogin(String email, LoginForm form) {
        if (form.getCode() == null || form.getCode().isBlank()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_REGISTERED);
        }
        verifyWithFailRecord(email, VerifyCodeService.SCENE_LOGIN, form.getCode());
        String username = uniqueUsernameFromEmail(email);
        try {
            return userService.createUser(username, form.getPassword(), email, null);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.ACCOUNT_EXISTS);
        }
    }

    /** 由邮箱前缀生成合法用户名（清洗非法字符、保证 3-20 位、冲突加数字后缀） */
    private String uniqueUsernameFromEmail(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9_]", "");
        if (base.length() < 3) {
            base = "user" + base;
        }
        if (base.length() > 20) {
            base = base.substring(0, 20);
        }
        String username = base;
        int i = 1;
        while (selectByUsername(username) != null && i <= 100) {
            String suffix = String.valueOf(i++);
            int keep = Math.max(1, 20 - suffix.length());
            username = base.substring(0, Math.min(base.length(), keep)) + suffix;
        }
        if (selectByUsername(username) != null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户名生成失败，请稍后重试");
        }
        return username;
    }

    private User selectByUsername(String username) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
    }

    private User selectByEmail(String email) {
        return userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    /**
     * Sa-Token 登录并组装登录返回（附角色编码，供前端按角色分流）
     *
     * <p>按「记住我」传入不同有效期：短会话 1 天 / 长会话 15 天（含活跃滑动过期）。
     */
    private LoginVO doLogin(User user, boolean rememberMe) {
        SaLoginParameter model = new SaLoginModel()
                .setTimeout(rememberMe ? TIMEOUT_REMEMBER : TIMEOUT_DEFAULT)
                .setActiveTimeout(rememberMe ? ACTIVE_TIMEOUT_REMEMBER : ACTIVE_TIMEOUT_DEFAULT);
        StpUtil.login(user.getId(), model);
        return LoginVO.builder()
                .token(StpUtil.getTokenValue())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .coinBalance(user.getCoinBalance())
                .role(userService.getUserRoleCode(user.getId()))
                .build();
    }

    /**
     * 超过失败次数上限 → 直接拒绝，不再查库比对密码（防爆破）
     */
    private void checkFailLimit(String account) {
        String ip = IpUtil.getClientIp();
        // 账号+IP 维度：同一 IP 对同一账号失败过多次（防单账号爆破，且不误伤真实用户从自己 IP 登录）
        if (isOverLimit(LOGIN_FAIL_KEY + ip + ":" + account, MAX_FAIL_COUNT)) {
            throw new BusinessException(ErrorCode.LOGIN_TOO_MANY);
        }
        // 纯 IP 维度：同一 IP 总失败过多次（防单 IP 批量爆破）
        if (isOverLimit(LOGIN_FAIL_IP_KEY + ip, MAX_IP_FAIL_COUNT)) {
            throw new BusinessException(ErrorCode.LOGIN_TOO_MANY);
        }
    }

    /**
     * 记录一次失败：账号+IP 维度与纯 IP 维度各计一次（INCR + 首次 EXPIRE 原子化，固定窗口）
     */
    private void recordFail(String account) {
        String ip = IpUtil.getClientIp();
        incrWithExpire(LOGIN_FAIL_KEY + ip + ":" + account, FAIL_WINDOW);
        incrWithExpire(LOGIN_FAIL_IP_KEY + ip, FAIL_WINDOW);
    }

    private boolean isOverLimit(String key, int limit) {
        String count = stringRedisTemplate.opsForValue().get(key);
        return count != null && Integer.parseInt(count) >= limit;
    }

    /** INCR + 首次 EXPIRE 用 Lua 原子完成，避免 EXPIRE 失败导致 key 永久存在（永久锁） */
    private void incrWithExpire(String key, Duration ttl) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(INCR_WITH_EXPIRE_LUA, Long.class);
        stringRedisTemplate.execute(script, List.of(key), String.valueOf(ttl.getSeconds()));
    }

    /** 校验验证码；错误/过期同时计入登录失败次数（防验证码爆破） */
    private void verifyWithFailRecord(String account, String scene, String code) {
        try {
            verifyCodeService.verify(scene, account, code);
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.CODE_ERROR || e.getErrorCode() == ErrorCode.CODE_EXPIRED) {
                recordFail(account);
            }
            throw e;
        }
    }
}
