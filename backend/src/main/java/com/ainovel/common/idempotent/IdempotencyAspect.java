package com.ainovel.common.idempotent;

import com.ainovel.common.annotation.Idempotent;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.IpUtil;
import com.ainovel.common.util.LoginUserUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 幂等键切面：同一个键仅实际执行一次，之后重放第一次的响应。
 *
 * <p><b>采用切面而非拦截器</b>：重放需先获取**方法返回值**。
 * 拦截器的 {@code afterCompletion} 阶段响应已写出、无法读取 body（在该位置包装一层
 * {@code ContentCachingResponseWrapper} 也可实现，但会将整个响应链一并纳入，
 * 而仅少数接口需要）。切面直接获取方法返回值，不涉及其它链路。
 *
 * <p>键的格式为 {@code idem:{维度}:{方法}:{调用方提供的键}}：
 * <ul>
 *   <li><b>维度</b>（登录用户 id，未登录则客户端 IP）必须包含，否则 A 的键会被 B 复用；</li>
 *   <li><b>方法</b>必须包含，否则同一个键用于两个接口会相互污染
 *       （在充值接口上取得的键，用于解锁接口时会直接读到充值的结果）；</li>
 *   <li>仅最后一段由调用方提供，因此它无法构造出跨越其它调用方的键，已通过白名单字符集限制。</li>
 * </ul>
 *
 * <p>键的两条处理路径（成功保留、失败删除）刻意分开：
 * <b>成功的操作已实际发生，重放它正是幂等应有的行为</b>；而失败基本均可由用户自行修复
 * （余额不足 → 充值），若将失败一并缓存，用户会以同一个键持续失败。
 *
 * <p>故障策略：Redis 不可用时会抛异常，导致整个接口不可用。此处与限流（fail-open）不同，
 * 为刻意设计。幂等键涉及**资金**：宁可让请求失败由客户端重试，也不能在「无法判断是否为重复请求」
 * 的情况下放行并执行一次扣币。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    /** 调用方声明「这是同一次操作」的请求头 */
    public static final String HEADER = "Idempotency-Key";

    /** 计数器 key 前缀，与限流的 rl:、计费的 ai:user:usage: 分开 */
    static final String KEY_PREFIX = "idem:";

    /**
     * 「正在处理」的占位值。
     *
     * <p>用一个不可能出现在 JSON 响应里的字符串（{\\u0000 开头）而不是 {@code "processing"}：
     * 若某个响应的正文恰为该词，也不会被误判为「处理中」而被长期拒绝。
     */
    static final String PROCESSING = "\u0000processing";

    /** 键的长度与字符集白名单：短于 8 位容易冲突，长于 128 位属于滥用，其余字符会污染 Redis key */
    private static final Pattern ALLOWED_KEY = Pattern.compile("^[A-Za-z0-9_.:\\-]{8,128}$");

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String rawKey = currentKey();
        if (rawKey == null) {
            // 未携带键 ⇒ 按原样执行。该头非必填，旧客户端不应因服务端引入幂等而被拦截
            return pjp.proceed();
        }
        String redisKey = buildKey(pjp, rawKey);

        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING,
                Duration.ofSeconds(idempotent.processingSeconds()));
        if (Boolean.TRUE.equals(acquired)) {
            return executeAndRemember(pjp, redisKey, idempotent.ttlSeconds());
        }

        String cached = stringRedisTemplate.opsForValue().get(redisKey);
        if (cached == null) {
            // 上一次操作恰处于「失败删键」与本次读取之间，窗口极窄。
            // 此处**不**直接执行：并发的多个请求会一并进入业务逻辑，等同于没有幂等。由客户端重新发起。
            throw new BusinessException(ErrorCode.REPEAT_SUBMIT, "上一次操作正在收尾，请稍后重试");
        }
        if (PROCESSING.equals(cached)) {
            throw new BusinessException(ErrorCode.REPEAT_SUBMIT, "这笔操作正在处理中，请勿重复提交");
        }
        return replay(cached, redisKey);
    }

    private Object executeAndRemember(ProceedingJoinPoint pjp, String redisKey, long ttlSeconds)
            throws Throwable {
        Object result;
        try {
            result = pjp.proceed();
        } catch (Throwable t) {
            // 失败立即释放键，同一键可重试（理由见类注释）
            stringRedisTemplate.delete(redisKey);
            throw t;
        }
        try {
            stringRedisTemplate.opsForValue().set(redisKey,
                    objectMapper.writeValueAsString(result), Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            // 写入失败不影响本次结果，但下次使用同一键会**重新执行**，
            // 该行为差异是静默的，因此记录 warn 而非 debug
            log.warn("幂等结果写入失败，该键下次会重新执行：key={}, err={}", redisKey, e.toString());
        }
        return result;
    }

    private Object replay(String cached, String redisKey) {
        try {
            // 按 ResponseDTO 读回，data 会成为 Map，序列化给前端的结果与第一次完全一致
            return objectMapper.readValue(cached, ResponseDTO.class);
        } catch (Exception e) {
            // 响应模型已变更，或缓存被写坏。删除该键并报错，由客户端重发：
            // 宁可多执行一次，也不能返回空响应使前端误认为「操作成功但无结果」
            stringRedisTemplate.delete(redisKey);
            log.warn("幂等结果反序列化失败，已丢弃该键：{}", e.toString());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "操作结果无法读取，请重新发起");
        }
    }

    /**
     * 从请求头取键并校验。
     *
     * <p>未携带返回 null（按原样执行）；携带但不合规范则**直接拒绝**，而非忽略。
     * 静默忽略等同于告知调用方「幂等已生效」，而实际并未生效，其风险高于直接报错。
     */
    private String currentKey() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        String key = attributes.getRequest().getHeader(HEADER);
        if (key == null || key.isBlank()) {
            return null;
        }
        key = key.trim();
        if (!ALLOWED_KEY.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "幂等键格式不合法：只允许 8~128 位的字母、数字与 _ . : -");
        }
        return key;
    }

    private String buildKey(ProceedingJoinPoint pjp, String rawKey) {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String method = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
        return KEY_PREFIX + currentDimension() + ":" + method + ":" + rawKey;
    }

    /** 与限流同一套维度口径：登录用户按 id，未登录按客户端 IP */
    private String currentDimension() {
        Long userId = LoginUserUtil.getUserIdOrNull();
        return userId != null ? "u:" + userId : "ip:" + IpUtil.getClientIp();
    }
}
