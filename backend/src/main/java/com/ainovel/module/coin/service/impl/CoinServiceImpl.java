package com.ainovel.module.coin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
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
import com.ainovel.module.user.domain.entity.User;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import com.ainovel.module.coin.service.PayNotifyService;
import com.ainovel.module.coin.service.CoinService;
import com.ainovel.module.user.service.UserService;

/**
 * 虚拟币服务：充值订单 + 模拟收银台 + 原子扣减 + 超时关单
 *
 * <p>充值链路：创建订单（含支付截止时间）→ 延迟队列超时关单 / 支付回调验签入账。
 * 真实第三方支付接入时，{@code /coin/pay/notify} 即回调入口（验签入账）；当前用
 * {@link #mockPay} 在服务端模拟网关生成签名回调，走同一套 {@link PayNotifyService}。
 */
@Service
@RequiredArgsConstructor
public class CoinServiceImpl implements CoinService {

    /** 订单列表单页上限（钳位，避免一次拉全表） */
    private static final int MAX_ORDER_PAGE_SIZE = 50;

    private final UserService userService;

    private final CoinLogMapper coinLogMapper;

    private final RechargeOrderMapper rechargeOrderMapper;

    private final MqSender mqSender;

    private final PayNotifyService payNotifyService;

    public int getBalance(Long userId) {
        User user = userService.getUser(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return user.getCoinBalance();
    }

    /**
     * 原子扣减（防超扣）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deduct(Long userId, int amount, CoinTypeEnum type, Long bizId, String remark) {
        if (!userService.deductCoin(userId, amount)) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_COIN);
        }
        saveLog(userId, -amount, type, bizId, remark);
    }

    /**
     * 原子加币（如反馈奖励）：加币与流水记录在同一事务内完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public void award(Long userId, int amount, CoinTypeEnum type, Long bizId, String remark) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "奖励币数必须大于 0");
        }
        if (!userService.addCoin(userId, amount)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        saveLog(userId, amount, type, bizId, remark);
    }

    /**
     * 创建充值订单：写入待支付订单 + 投递延迟关单消息，返回订单号。
     *
     * <p>下单与投递消息同一事务（消息由 {@link MqSender#sendAfterCommit} 在事务提交后发出），
     * 保证「订单已持久化则关单消息必达」；超时未支付由 {@link OrderCloseConsumer} 自动关单。
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeVO createRecharge(Long userId, int amount) {
        if (amount <= 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "充值金额必须大于 0");
        }
        // 复用存量单：一个用户同一时刻最多一笔待支付，存在则直接返回，避免重复下单
        RechargeOrder existing = rechargeOrderMapper.selectLatestPending(userId);
        if (existing != null) {
            if (existing.getExpireTime() != null && existing.getExpireTime().isBefore(LocalDateTime.now())) {
                // 已过期但 MQ 尚未关单：主动取消后建新单
                rechargeOrderMapper.markCanceled(existing.getOrderNo());
            } else {
                return toVO(existing, true);
            }
        }

        RechargeOrder order = new RechargeOrder();
        order.setOrderNo(IdUtil.getSnowflakeNextIdStr());
        order.setUserId(userId);
        order.setCoinAmount(amount);
        order.setPayAmount(BigDecimal.valueOf(amount));
        order.setStatus(OrderStatusEnum.PENDING.getCode());
        order.setExpireTime(LocalDateTime.now().plusSeconds(MqConstant.PAY_ORDER_CLOSE_DELAY_MS / 1000));
        rechargeOrderMapper.insert(order);

        OrderCloseMessage message = new OrderCloseMessage();
        message.setOrderNo(order.getOrderNo());
        mqSender.sendAfterCommit(MqConstant.PAY_DELAY_EXCHANGE, MqConstant.PAY_DELAY_ROUTING_KEY, message);
        return toVO(order, false);
    }

    private RechargeVO toVO(RechargeOrder order, boolean reused) {
        RechargeVO vo = new RechargeVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setCoinAmount(order.getCoinAmount());
        vo.setReused(reused);
        long remain = order.getExpireTime() == null ? 0
                : Math.max(0, Duration.between(LocalDateTime.now(), order.getExpireTime()).getSeconds());
        vo.setRemainSeconds((int) remain);
        return vo;
    }

    /**
     * 模拟收银台支付：服务端扮演第三方网关，生成签名回调后走统一入账逻辑。
     *
     * <p>归属校验（只能支付自己的订单）在此处完成；真正的验签入账在
     * {@link PayNotifyService#handleNotify}，签名/密钥全程不出后端。
     */
    public void mockPay(Long userId, String orderNo) {
        RechargeOrder order = rechargeOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (order.getStatus() == OrderStatusEnum.PAID.getCode()) {
            return;
        }
        if (order.getStatus() == OrderStatusEnum.CANCELED.getCode()) {
            throw new BusinessException(ErrorCode.ORDER_CLOSED);
        }
        if (order.getExpireTime() != null && order.getExpireTime().isBefore(LocalDateTime.now())) {
            // 已过期（MQ 关单可能有秒级延迟）：服务端兜底拒绝支付
            throw new BusinessException(ErrorCode.ORDER_CLOSED);
        }
        PayNotifyForm form = payNotifyService.buildNotifyForm(order.getOrderNo(), order.getCoinAmount());
        payNotifyService.handleNotify(form);
    }

    /**
     * 超时关单：把仍未支付的订单置为已取消（幂等）。
     *
     * <p>{@code markCanceled} 原子要求 status=0，已支付/已取消订单均返回 0，天然幂等，
     * 不会误关已支付订单。
     */
    public void closeTimeoutOrder(String orderNo) {
        rechargeOrderMapper.markCanceled(orderNo);
    }

    /**
     * 取消充值订单：待支付 -> 已取消（幂等），已支付订单不可取消。
     */
    public void cancelOrder(Long userId, String orderNo) {
        RechargeOrder order = rechargeOrderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (order.getStatus() == OrderStatusEnum.CANCELED.getCode()) {
            return;
        }
        if (order.getStatus() == OrderStatusEnum.PAID.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "订单已支付，无法取消");
        }
        rechargeOrderMapper.markCanceled(orderNo);
    }

    /**
     * 我的充值订单分页（仅查询当前用户的订单）。
     *
     * <p>查询条件中的 {@code userId} 恒取自登录态而非请求参数，避免越权查询其他用户的订单；
     * 页码与页大小做钳位，防止一次拉取全表。时间区间为左闭右开，由调用方按「含首含尾」的
     * 自然语言区间换算后再传入。
     */
    public PageResult<MyRechargeOrderVO> pageMyOrders(Long userId, int pageNum, int pageSize, Integer status,
                                                      LocalDateTime startTime, LocalDateTime endTime) {
        int safePageNum = Math.max(1, pageNum);
        int safePageSize = Math.min(Math.max(1, pageSize), MAX_ORDER_PAGE_SIZE);
        LambdaQueryWrapper<RechargeOrder> wrapper = new LambdaQueryWrapper<RechargeOrder>()
                .eq(RechargeOrder::getUserId, userId)
                .eq(status != null, RechargeOrder::getStatus, status)
                .ge(startTime != null, RechargeOrder::getCreateTime, startTime)
                .lt(endTime != null, RechargeOrder::getCreateTime, endTime)
                .orderByDesc(RechargeOrder::getCreateTime);
        Page<RechargeOrder> page = rechargeOrderMapper.selectPage(
                new Page<>(safePageNum, safePageSize), wrapper);
        List<MyRechargeOrderVO> list = page.getRecords().stream()
                .map(o -> BeanUtil.copyProperties(o, MyRechargeOrderVO.class))
                .toList();
        return PageResult.of(page.getTotal(), safePageNum, safePageSize, list);
    }

    private void saveLog(Long userId, int changeAmount, CoinTypeEnum type, Long bizId, String remark) {
        CoinLog log = new CoinLog();
        log.setUserId(userId);
        log.setChangeAmount(changeAmount);
        log.setType(type.getCode());
        log.setBizId(bizId);
        log.setRemark(remark);
        coinLogMapper.insert(log);
    }
}
