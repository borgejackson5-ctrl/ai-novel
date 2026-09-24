package com.ainovel.module.coin.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.OrderStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.PaySignUtil;
import com.ainovel.module.coin.dao.CoinLogMapper;
import com.ainovel.module.coin.dao.RechargeOrderMapper;
import com.ainovel.module.coin.domain.entity.CoinLog;
import com.ainovel.module.coin.domain.entity.RechargeOrder;
import com.ainovel.module.coin.domain.form.PayNotifyForm;
import com.ainovel.module.coin.service.impl.PayNotifyServiceImpl;
import com.ainovel.module.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 支付回调服务单测：验签 → 时间戳 → nonce 防重放 → 金额校验 → 幂等入账 全链路
 */
@ExtendWith(MockitoExtension.class)
class PayNotifyServiceTest {

    private static final String SECRET = "test-secret";

    @Mock
    private UserService userService;
    @Mock
    private CoinLogMapper coinLogMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private PayNotifyService payNotifyService;

    @BeforeEach
    void setUp() {
        payNotifyService = new PayNotifyServiceImpl(userService, coinLogMapper, rechargeOrderMapper, stringRedisTemplate);
        ReflectionTestUtils.setField(payNotifyService, "payNotifySecret", SECRET);
        // lenient：部分测试（验签失败/时间戳过期/buildNotifyForm）不触发 Redis，避免 strict stubbing 报错
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private PayNotifyForm buildForm(String orderNo, int amount) {
        return buildForm(orderNo, amount, System.currentTimeMillis(), "nonce-1");
    }

    private PayNotifyForm buildForm(String orderNo, int amount, long timestamp, String nonce) {
        PayNotifyForm form = new PayNotifyForm();
        form.setOrderNo(orderNo);
        form.setAmount(amount);
        form.setTimestamp(timestamp);
        form.setNonce(nonce);
        form.setSign(PaySignUtil.sign(orderNo, amount, timestamp, nonce, SECRET));
        return form;
    }

    @Test
    @DisplayName("回调成功 → 验签通过 → markPaid + 加币 + 记流水")
    void handleNotify_success() {
        PayNotifyForm form = buildForm("NO123", 100);
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.PENDING.getCode());
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);
        when(rechargeOrderMapper.markPaid("NO123")).thenReturn(1);

        payNotifyService.handleNotify(form);

        verify(rechargeOrderMapper).markPaid("NO123");
        verify(userService).addCoin(1L, 100);
        verify(coinLogMapper).insert(argThat((CoinLog log) ->
                log.getChangeAmount() == 100 && "CHARGE".equals(log.getType())));
    }

    @Test
    @DisplayName("签名错误 → 抛 SIGN_ERROR，不碰余额/订单")
    void handleNotify_signError() {
        PayNotifyForm form = buildForm("NO123", 100);
        form.setSign("bad-sign");

        BusinessException ex = assertThrows(BusinessException.class, () -> payNotifyService.handleNotify(form));
        assertEquals(ErrorCode.SIGN_ERROR, ex.getErrorCode());
        verify(userService, never()).addCoin(anyLong(), anyInt());
        verify(rechargeOrderMapper, never()).markPaid(anyString());
    }

    @Test
    @DisplayName("时间戳过期（超 5 分钟）→ 抛 SIGN_TIMEOUT")
    void handleNotify_timeout() {
        long stale = System.currentTimeMillis() - 10 * 60 * 1000L;
        PayNotifyForm form = buildForm("NO123", 100, stale, "nonce-1");

        BusinessException ex = assertThrows(BusinessException.class, () -> payNotifyService.handleNotify(form));
        assertEquals(ErrorCode.SIGN_TIMEOUT, ex.getErrorCode());
    }

    @Test
    @DisplayName("nonce 重复 → 抛 PAY_REPLAY")
    void handleNotify_replay() {
        PayNotifyForm form = buildForm("NO123", 100);
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> payNotifyService.handleNotify(form));
        assertEquals(ErrorCode.PAY_REPLAY, ex.getErrorCode());
    }

    @Test
    @DisplayName("回调金额与订单不符 → 抛 SIGN_ERROR")
    void handleNotify_amountMismatch() {
        PayNotifyForm form = buildForm("NO123", 100);
        RechargeOrder order = buildOrder("NO123", 1L, 200, OrderStatusEnum.PENDING.getCode());
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> payNotifyService.handleNotify(form));
        assertEquals(ErrorCode.SIGN_ERROR, ex.getErrorCode());
        verify(userService, never()).addCoin(anyLong(), anyInt());
    }

    @Test
    @DisplayName("订单已支付 → markPaid 返回 0 → 幂等返回，不重复入账")
    void handleNotify_alreadyPaid() {
        PayNotifyForm form = buildForm("NO123", 100);
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.PENDING.getCode());
        RechargeOrder paid = buildOrder("NO123", 1L, 100, OrderStatusEnum.PAID.getCode());
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order, paid);
        when(rechargeOrderMapper.markPaid("NO123")).thenReturn(0);

        payNotifyService.handleNotify(form);

        verify(userService, never()).addCoin(anyLong(), anyInt());
        verify(coinLogMapper, never()).insert(any(CoinLog.class));
    }

    @Test
    @DisplayName("订单已取消（超时关单）→ 抛 ORDER_CLOSED")
    void handleNotify_orderClosed() {
        PayNotifyForm form = buildForm("NO123", 100);
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.PENDING.getCode());
        RechargeOrder canceled = buildOrder("NO123", 1L, 100, OrderStatusEnum.CANCELED.getCode());
        when(valueOperations.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order, canceled);
        when(rechargeOrderMapper.markPaid("NO123")).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> payNotifyService.handleNotify(form));
        assertEquals(ErrorCode.ORDER_CLOSED, ex.getErrorCode());
    }

    @Test
    @DisplayName("buildNotifyForm 生成可被 verify 通过的签名")
    void buildNotifyForm_verifiable() {
        PayNotifyForm form = payNotifyService.buildNotifyForm("NO123", 100);
        assertTrue(PaySignUtil.verify(form.getOrderNo(), form.getAmount(),
                form.getTimestamp(), form.getNonce(), form.getSign(), SECRET));
    }

    private RechargeOrder buildOrder(String orderNo, Long userId, int amount, int status) {
        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(orderNo);
        order.setUserId(userId);
        order.setCoinAmount(amount);
        order.setStatus(status);
        return order;
    }
}
