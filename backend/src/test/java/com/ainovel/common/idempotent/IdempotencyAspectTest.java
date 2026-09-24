package com.ainovel.common.idempotent;

import com.ainovel.common.annotation.Idempotent;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 幂等键切面的判据测试。
 *
 * <p>重点在四条分支的边界，而不是「能否运行」：
 * <ul>
 *   <li>未带键 ⇒ 完全不访问 Redis（不能因为服务端引入幂等而改变旧客户端的行为）；</li>
 *   <li>抢到键 ⇒ 执行并把结果存下来（只执行不存储，下次重试会重跑，等同于未实现幂等）；</li>
 *   <li>另一请求处理中 ⇒ 409，而不是排队等待或直接执行；</li>
 *   <li>业务失败 ⇒ 键必须删除，否则用户会被同一个键永久挡住。</li>
 * </ul>
 *
 * <p>键的规范性同样覆盖：带了但不合法要直接拒绝。静默忽略等于告诉调用方
 * 「幂等已生效」而实际未生效，其影响大于直接报错。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdempotencyAspectTest {

    private static final String RAW_KEY = "order-retry-0001-abcdef";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private ProceedingJoinPoint pjp;
    @Mock
    private MethodSignature signature;
    @Mock
    private Idempotent idempotent;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IdempotencyAspect aspect;

    @BeforeEach
    void init() {
        aspect = new IdempotencyAspect(stringRedisTemplate, objectMapper);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn(IdempotencyAspectTest.class);
        when(signature.getName()).thenReturn("recharge");
        when(idempotent.ttlSeconds()).thenReturn(86400L);
        when(idempotent.processingSeconds()).thenReturn(60L);
    }

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 造一个带（或不带）幂等键的请求上下文 */
    private void withRequest(String idempotencyKey) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (idempotencyKey != null) {
            request.addHeader(IdempotencyAspect.HEADER, idempotencyKey);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    @DisplayName("没带幂等键 → 原样执行，一次都不碰 Redis")
    void noKeyRunsNormally() throws Throwable {
        withRequest(null);
        ResponseDTO<Void> expected = ResponseDTO.ok();
        when(pjp.proceed()).thenReturn(expected);

        Object result = aspect.around(pjp, idempotent);

        assertEquals(expected, result);
        verify(pjp, times(1)).proceed();
        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("第一次执行 → 抢到键、执行、并把响应存下来")
    void firstExecutionStoresResult() throws Throwable {
        withRequest(RAW_KEY);
        ResponseDTO<Map<String, Object>> expected = ResponseDTO.ok(Map.of("orderNo", "1001"));
        when(pjp.proceed()).thenReturn(expected);
        when(valueOps.setIfAbsent(anyString(), eq(IdempotencyAspect.PROCESSING), any(Duration.class)))
                .thenReturn(true);

        Object result = aspect.around(pjp, idempotent);

        assertEquals(expected, result);

        // 只执行不存储 ⇒ 下次同一键会重跑，等同于未实现幂等
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), json.capture(), any(Duration.class));
        assertEquals(true, json.getValue().contains("1001"));

        // 键中必须同时包含维度与方法，否则会跨用户 / 跨接口串用
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).setIfAbsent(key.capture(), anyString(), any(Duration.class));
        assertEquals(true, key.getValue().startsWith(IdempotencyAspect.KEY_PREFIX));
        assertEquals(true, key.getValue().contains("IdempotencyAspectTest.recharge"));
        assertEquals(true, key.getValue().endsWith(RAW_KEY));
    }

    @Test
    @DisplayName("上一次还在处理中 → 409（不排队、也不重复执行）")
    void inFlightRejected() throws Throwable {
        withRequest(RAW_KEY);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(IdempotencyAspect.PROCESSING);

        BusinessException ex = assertThrows(BusinessException.class, () -> aspect.around(pjp, idempotent));

        assertEquals(ErrorCode.REPEAT_SUBMIT, ex.getErrorCode());
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("已经完成过 → 直接重放第一次的响应，业务方法不再执行")
    void replaysStoredResult() throws Throwable {
        withRequest(RAW_KEY);
        String stored = objectMapper.writeValueAsString(ResponseDTO.ok(Map.of("orderNo", "1001")));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(valueOps.get(anyString())).thenReturn(stored);

        Object result = aspect.around(pjp, idempotent);

        ResponseDTO<?> dto = (ResponseDTO<?>) result;
        assertNotNull(dto);
        assertEquals(Boolean.TRUE, dto.getSuccess());
        assertEquals(ErrorCode.OK.getCode(), dto.getCode());
        // 幂等的意义在于：重复请求得到的是第一次的结果，而不是「已执行过」的提示
        assertEquals("1001", String.valueOf(((Map<?, ?>) dto.getData()).get("orderNo")));
        verify(pjp, never()).proceed();
    }

    @Test
    @DisplayName("业务失败 → 键立刻删掉，同一个键可以重试")
    void failureReleasesKey() throws Throwable {
        withRequest(RAW_KEY);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(pjp.proceed()).thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_COIN));

        assertThrows(BusinessException.class, () -> aspect.around(pjp, idempotent));

        // 不删除时，「余额不足」这类用户可自行修复的情况会被永久挡住：充值后重试仍然失败
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).delete(key.capture());
        assertEquals(true, key.getValue().endsWith(RAW_KEY));
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("键格式不合法 → 直接拒绝（静默忽略比报错危险）")
    void malformedKeyRejected() {
        withRequest("短");   // 少于 8 位

        BusinessException ex = assertThrows(BusinessException.class, () -> aspect.around(pjp, idempotent));

        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
    }
}
