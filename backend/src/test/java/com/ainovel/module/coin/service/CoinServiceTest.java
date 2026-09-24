package com.ainovel.module.coin.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MqConstant;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.CoinTypeEnum;
import com.ainovel.common.enums.OrderStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.mq.MqSender;
import com.ainovel.module.coin.dao.CoinLogMapper;
import com.ainovel.module.coin.dao.RechargeOrderMapper;
import com.ainovel.module.coin.domain.entity.CoinLog;
import com.ainovel.module.coin.domain.entity.RechargeOrder;
import com.ainovel.module.coin.domain.form.PayNotifyForm;
import com.ainovel.module.coin.domain.message.OrderCloseMessage;
import com.ainovel.module.coin.domain.vo.MyRechargeOrderVO;
import com.ainovel.module.coin.domain.vo.RechargeVO;
import com.ainovel.module.coin.service.impl.CoinServiceImpl;
import com.ainovel.module.user.service.UserService;
import com.ainovel.module.user.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 虚拟币服务单测：原子扣减、下单（含延迟关单消息）、模拟支付（走回调入账）、超时关单
 */
@ExtendWith(MockitoExtension.class)
class CoinServiceTest {

    @Mock
    private UserService userService;
    @Mock
    private CoinLogMapper coinLogMapper;
    @Mock
    private RechargeOrderMapper rechargeOrderMapper;
    @Mock
    private MqSender mqSender;
    @Mock
    private PayNotifyService payNotifyService;

    private CoinService coinService;

    @BeforeEach
    void initService() {
        coinService = new CoinServiceImpl(userService, coinLogMapper, rechargeOrderMapper, mqSender, payNotifyService);
    }

    @Test
    @DisplayName("扣减成功 → deductCoin 返回 1 + 记负流水")
    void deduct_success() {
        when(userService.deductCoin(1L, 50)).thenReturn(true);

        coinService.deduct(1L, 50, CoinTypeEnum.UNLOCK, 100L, "解锁章节");

        verify(coinLogMapper).insert(argThat((CoinLog log) ->
                log.getChangeAmount() == -50 &&
                        "UNLOCK".equals(log.getType()) &&
                        log.getBizId().equals(100L)));
    }

    @Test
    @DisplayName("余额不足 → deductCoin 返回 0 → 抛 INSUFFICIENT_COIN，不记流水")
    void deduct_insufficient_throws() {
        when(userService.deductCoin(1L, 99999)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coinService.deduct(1L, 99999, CoinTypeEnum.UNLOCK, 100L, "解锁章节"));
        assertEquals(ErrorCode.INSUFFICIENT_COIN, ex.getErrorCode());
        verify(coinLogMapper, never()).insert(any(CoinLog.class));
    }

    @Test
    @DisplayName("创建充值订单 → 待支付单 + 设置支付截止时间 + 发送延迟关单消息")
    void createRecharge_success() {
        when(rechargeOrderMapper.insert(any(RechargeOrder.class))).thenReturn(1);

        RechargeVO vo = coinService.createRecharge(1L, 100);

        assertNotNull(vo.getOrderNo());
        assertFalse(vo.getReused());
        verify(rechargeOrderMapper).insert(argThat((RechargeOrder o) ->
                o.getStatus() == OrderStatusEnum.PENDING.getCode() &&
                        o.getCoinAmount() == 100 &&
                        o.getUserId().equals(1L) &&
                        o.getExpireTime() != null));
        verify(mqSender).sendAfterCommit(eq(MqConstant.PAY_DELAY_EXCHANGE),
                eq(MqConstant.PAY_DELAY_ROUTING_KEY),
                argThat((OrderCloseMessage m) -> m.getOrderNo().equals(vo.getOrderNo())));
        // 下单阶段不得触碰余额
        verify(userService, never()).addCoin(anyLong(), anyInt());
    }

    @Test
    @DisplayName("充值金额非法 → 抛 PARAM_ERROR，不碰 DB/消息")
    void createRecharge_invalidAmount_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> coinService.createRecharge(1L, 0));
        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verifyNoInteractions(rechargeOrderMapper);
        verifyNoInteractions(mqSender);
    }

    @Test
    @DisplayName("模拟支付成功 → 生成签名回调并走 handleNotify 入账")
    void mockPay_success() {
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.PENDING.getCode());
        PayNotifyForm form = new PayNotifyForm();
        form.setOrderNo("NO123");
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);
        when(payNotifyService.buildNotifyForm("NO123", 100)).thenReturn(form);

        coinService.mockPay(1L, "NO123");

        verify(payNotifyService).buildNotifyForm("NO123", 100);
        verify(payNotifyService).handleNotify(form);
    }

    @Test
    @DisplayName("模拟支付：订单不存在 → 抛 NOT_FOUND")
    void mockPay_orderNotFound_throws() {
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coinService.mockPay(1L, "NO123"));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(payNotifyService, never()).handleNotify(any());
    }

    @Test
    @DisplayName("模拟支付：非本人订单 → 抛 FORBIDDEN（防越权入账）")
    void mockPay_otherUsersOrder_throws() {
        RechargeOrder order = buildOrder("NO123", 2L, 100, OrderStatusEnum.PENDING.getCode());
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coinService.mockPay(1L, "NO123"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
        verify(payNotifyService, never()).handleNotify(any());
    }

    @Test
    @DisplayName("模拟支付：订单已支付 → 幂等直接返回")
    void mockPay_alreadyPaid_idempotent() {
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.PAID.getCode());
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);

        coinService.mockPay(1L, "NO123");

        verify(payNotifyService, never()).buildNotifyForm(anyString(), anyInt());
        verify(payNotifyService, never()).handleNotify(any());
    }

    @Test
    @DisplayName("模拟支付：订单已取消 → 抛 ORDER_CLOSED")
    void mockPay_canceled_throws() {
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.CANCELED.getCode());
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coinService.mockPay(1L, "NO123"));
        assertEquals(ErrorCode.ORDER_CLOSED, ex.getErrorCode());
    }

    @Test
    @DisplayName("超时关单：待支付订单 → markCanceled 返回 1")
    void closeTimeoutOrder_pending() {
        when(rechargeOrderMapper.markCanceled("NO123")).thenReturn(1);

        coinService.closeTimeoutOrder("NO123");

        verify(rechargeOrderMapper).markCanceled("NO123");
    }

    @Test
    @DisplayName("超时关单：已支付/已取消 → markCanceled 返回 0，幂等不抛错")
    void closeTimeoutOrder_idempotent() {
        when(rechargeOrderMapper.markCanceled("NO123")).thenReturn(0);

        coinService.closeTimeoutOrder("NO123");

        verify(rechargeOrderMapper).markCanceled("NO123");
    }

    @Test
    @DisplayName("复用存量单：存在未支付订单 → 直接返回该单，不新建不投递消息")
    void createRecharge_reuseExisting() {
        RechargeOrder existing = buildOrder("OLD123", 1L, 100, OrderStatusEnum.PENDING.getCode());
        existing.setExpireTime(LocalDateTime.now().plusMinutes(5));
        when(rechargeOrderMapper.selectLatestPending(1L)).thenReturn(existing);

        RechargeVO vo = coinService.createRecharge(1L, 100);

        assertEquals("OLD123", vo.getOrderNo());
        assertTrue(vo.getReused());
        verify(rechargeOrderMapper, never()).insert(any(RechargeOrder.class));
        verify(mqSender, never()).sendAfterCommit(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("复用存量单但已过期 → 取消旧单后新建")
    void createRecharge_existingExpired_cancelsAndCreates() {
        RechargeOrder expired = buildOrder("OLD123", 1L, 100, OrderStatusEnum.PENDING.getCode());
        expired.setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(rechargeOrderMapper.selectLatestPending(1L)).thenReturn(expired);
        when(rechargeOrderMapper.insert(any(RechargeOrder.class))).thenReturn(1);

        RechargeVO vo = coinService.createRecharge(1L, 100);

        verify(rechargeOrderMapper).markCanceled("OLD123");
        verify(rechargeOrderMapper).insert(any(RechargeOrder.class));
        assertFalse(vo.getReused());
    }

    @Test
    @DisplayName("模拟支付：订单已过期 → 抛 ORDER_CLOSED")
    void mockPay_expired_throws() {
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.PENDING.getCode());
        order.setExpireTime(LocalDateTime.now().minusSeconds(1));
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coinService.mockPay(1L, "NO123"));
        assertEquals(ErrorCode.ORDER_CLOSED, ex.getErrorCode());
        verify(payNotifyService, never()).handleNotify(any());
    }

    @Test
    @DisplayName("取消订单：待支付 → markCanceled")
    void cancelOrder_pending() {
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.PENDING.getCode());
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);
        when(rechargeOrderMapper.markCanceled("NO123")).thenReturn(1);

        coinService.cancelOrder(1L, "NO123");

        verify(rechargeOrderMapper).markCanceled("NO123");
    }

    @Test
    @DisplayName("取消订单：已支付 → 抛 PARAM_ERROR，不调用 markCanceled")
    void cancelOrder_paid_throws() {
        RechargeOrder order = buildOrder("NO123", 1L, 100, OrderStatusEnum.PAID.getCode());
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coinService.cancelOrder(1L, "NO123"));
        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        verify(rechargeOrderMapper, never()).markCanceled(anyString());
    }

    @Test
    @DisplayName("取消订单：他人订单 → 抛 FORBIDDEN")
    void cancelOrder_otherUsers_throws() {
        RechargeOrder order = buildOrder("NO123", 2L, 100, OrderStatusEnum.PENDING.getCode());
        when(rechargeOrderMapper.selectByOrderNo("NO123")).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> coinService.cancelOrder(1L, "NO123"));
        assertEquals(ErrorCode.FORBIDDEN, ex.getErrorCode());
    }

    @Test
    @DisplayName("我的订单分页：返回当前用户自己的单，字段映射正确")
    void pageMyOrders_mapsFields() {
        RechargeOrder paid = buildOrder("NO1", 1L, 100, OrderStatusEnum.PAID.getCode());
        paid.setCreateTime(LocalDateTime.now());
        Page<RechargeOrder> page = new Page<>(1, 10);
        page.setRecords(List.of(paid));
        page.setTotal(1);
        when(rechargeOrderMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<MyRechargeOrderVO> result = coinService.pageMyOrders(1L, 1, 10, null, null, null);

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals("NO1", result.getList().get(0).getOrderNo());
        assertEquals(100, result.getList().get(0).getCoinAmount());
        assertEquals(OrderStatusEnum.PAID.getCode(), result.getList().get(0).getStatus());
    }

    @Test
    @DisplayName("我的订单分页：页码/页大小越界时被钳位（pageNum<1 → 1，pageSize>50 → 50）")
    void pageMyOrders_clampsPageParams() {
        Page<RechargeOrder> page = new Page<>(1, 50);
        page.setRecords(List.of());
        page.setTotal(0);
        when(rechargeOrderMapper.selectPage(any(), any())).thenReturn(page);

        coinService.pageMyOrders(1L, 0, 999, null, null, null);

        ArgumentCaptor<Page<RechargeOrder>> captor = ArgumentCaptor.forClass(Page.class);
        verify(rechargeOrderMapper).selectPage(captor.capture(), any());
        assertEquals(1L, captor.getValue().getCurrent());
        assertEquals(50L, captor.getValue().getSize());
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
