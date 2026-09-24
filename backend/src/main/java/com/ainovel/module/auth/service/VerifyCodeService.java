package com.ainovel.module.auth.service;

import com.ainovel.module.auth.sender.CodeSender;

/**
 * 验证码服务：生成 6 位数字验证码 → 存 Redis → 调 {@link CodeSender} 发送；校验后一次性删除。
 *
 * <p>存储：{@code auth:code:{scene}:{target}}，TTL 5 分钟；60 秒内同目标限发一次（防刷）。
 * 校验失败累计尝试次数（{@code auth:code:attempt:{scene}:{target}}），超过 5 次作废该码，防爆破。
 */
public interface VerifyCodeService {

    /** 场景：注册 */
    public static final String SCENE_REGISTER = "register";

    /** 场景：登录（邮箱未注册转注册） */
    public static final String SCENE_LOGIN = "login";

    /** 场景：忘记密码重置 */
    public static final String SCENE_RESET = "reset";

    /** 生成并发送验证码（60 秒内同目标限发一次） */
    public void send(String scene, String target);

    /** 校验验证码：正确则删除（一次性，防重放）；错误累计尝试次数，超限作废 */
    public void verify(String scene, String target, String code);
}
