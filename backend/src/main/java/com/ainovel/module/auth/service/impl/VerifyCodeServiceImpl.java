package com.ainovel.module.auth.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.auth.sender.CodeSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.time.Duration;
import com.ainovel.module.auth.service.VerifyCodeService;

/**
 * 验证码服务：生成 6 位数字验证码 → 存 Redis → 调 {@link CodeSender} 发送；校验后一次性删除。
 *
 * <p>存储：{@code auth:code:{scene}:{target}}，TTL 5 分钟；60 秒内同目标限发一次（防刷）。
 * 校验失败累计尝试次数（{@code auth:code:attempt:{scene}:{target}}），超过 5 次作废该码，防爆破。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerifyCodeServiceImpl implements VerifyCodeService {

    /** 场景：注册 */
    public static final String SCENE_REGISTER = "register";
    /** 场景：登录（邮箱未注册转注册） */
    public static final String SCENE_LOGIN = "login";
    /** 场景：忘记密码重置 */
    public static final String SCENE_RESET = "reset";

    private static final String CODE_KEY = "auth:code:";
    private static final String LIMIT_KEY = "auth:code:limit:";
    private static final String ATTEMPT_KEY = "auth:code:attempt:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration LIMIT_TTL = Duration.ofSeconds(60);

    /** 校验失败次数上限，超过则作废验证码（防爆破） */
    private static final int MAX_VERIFY_ATTEMPT = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate stringRedisTemplate;

    private final CodeSender codeSender;

    /** 生成并发送验证码（60 秒内同目标限发一次） */
    public void send(String scene, String target) {
        checkScene(scene);
        String limitKey = LIMIT_KEY + scene + ":" + target;
        Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(limitKey, "1", LIMIT_TTL);
        if (ok == null || !ok) {
            throw new BusinessException(ErrorCode.RATE_LIMIT, "验证码发送太频繁，请稍后再试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1000000));
        // 发新码即重置该目标的校验失败计数
        stringRedisTemplate.delete(ATTEMPT_KEY + scene + ":" + target);
        stringRedisTemplate.opsForValue().set(CODE_KEY + scene + ":" + target, code, CODE_TTL);
        try {
            codeSender.send(target, code);
        } catch (Exception e) {
            // SMTP 发送失败：清除已写入的验证码与 60 秒限频 key，避免残留码占位与限频阻塞，使用户可立即重试
            stringRedisTemplate.delete(CODE_KEY + scene + ":" + target);
            stringRedisTemplate.delete(limitKey);
            log.error("验证码发送失败 scene={} target={}", scene, target, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "验证码发送失败，请稍后重试", e);
        }
    }

    /** 校验验证码：正确则删除（一次性，防重放）；错误累计尝试次数，超限作废 */
    public void verify(String scene, String target, String code) {
        checkScene(scene);
        String key = CODE_KEY + scene + ":" + target;
        String saved = stringRedisTemplate.opsForValue().get(key);
        if (saved == null) {
            throw new BusinessException(ErrorCode.CODE_EXPIRED);
        }
        if (!saved.equals(code)) {
            String attemptKey = ATTEMPT_KEY + scene + ":" + target;
            Long attempts = stringRedisTemplate.opsForValue().increment(attemptKey);
            if (attempts != null && attempts == 1L) {
                stringRedisTemplate.expire(attemptKey, CODE_TTL);
            }
            if (attempts != null && attempts >= MAX_VERIFY_ATTEMPT) {
                // 尝试次数达上限：作废验证码（防爆破），用户需重新获取
                stringRedisTemplate.delete(key);
                stringRedisTemplate.delete(attemptKey);
                throw new BusinessException(ErrorCode.CODE_EXPIRED, "验证码错误次数过多，已失效，请重新获取");
            }
            throw new BusinessException(ErrorCode.CODE_ERROR);
        }
        stringRedisTemplate.delete(key);
        stringRedisTemplate.delete(ATTEMPT_KEY + scene + ":" + target);
    }

    private void checkScene(String scene) {
        if (!SCENE_REGISTER.equals(scene) && !SCENE_LOGIN.equals(scene) && !SCENE_RESET.equals(scene)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "非法验证码场景");
        }
    }
}
