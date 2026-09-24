package com.ainovel.module.coin.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.CoinTypeEnum;
import com.ainovel.common.mq.MqSender;
import com.ainovel.module.coin.domain.vo.MyRechargeOrderVO;
import com.ainovel.module.coin.domain.vo.RechargeVO;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/**
 * 虚拟币服务：充值订单 + 模拟收银台 + 原子扣减 + 超时关单
 *
 * <p>充值链路：创建订单（含支付截止时间）→ 延迟队列超时关单 / 支付回调验签入账。
 * 真实第三方支付接入时，{@code /coin/pay/notify} 即回调入口（验签入账）；当前用
 * {@link #mockPay} 在服务端模拟网关生成签名回调，走同一套 {@link PayNotifyService}。
 */
public interface CoinService {

    public int getBalance(Long userId);

    /**
     * 原子扣减（防超扣）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deduct(Long userId, int amount, CoinTypeEnum type, Long bizId, String remark);

    /**
     * 原子加币（如反馈奖励）：加币与流水记录在同一事务内完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public void award(Long userId, int amount, CoinTypeEnum type, Long bizId, String remark);

    /**
     * 创建充值订单：写入待支付订单 + 投递延迟关单消息，返回订单号。
     *
     * <p>下单与投递消息同一事务（消息由 {@link MqSender#sendAfterCommit} 在事务提交后发出），
     * 保证「订单已持久化则关单消息必达」；超时未支付由 {@link OrderCloseConsumer} 自动关单。
     */
    @Transactional(rollbackFor = Exception.class)
    public RechargeVO createRecharge(Long userId, int amount);

    /**
     * 模拟收银台支付：服务端扮演第三方网关，生成签名回调后走统一入账逻辑。
     *
     * <p>归属校验（只能支付自己的订单）在此处完成；真正的验签入账在
     * {@link PayNotifyService#handleNotify}，签名/密钥全程不出后端。
     */
    public void mockPay(Long userId, String orderNo);

    /**
     * 超时关单：把仍未支付的订单置为已取消（幂等）。
     *
     * <p>{@code markCanceled} 原子要求 status=0，已支付/已取消订单均返回 0，天然幂等，
     * 不会误关已支付订单。
     */
    public void closeTimeoutOrder(String orderNo);

    /**
     * 取消充值订单：待支付 -> 已取消（幂等），已支付订单不可取消。
     */
    public void cancelOrder(Long userId, String orderNo);

    /**
     * 我的充值订单分页（仅查询当前用户的订单）。
     *
     * <p>查询条件中的 {@code userId} 恒取自登录态而非请求参数，避免越权查询其他用户的订单；
     * 页码与页大小做钳位，防止一次拉取全表。时间区间为左闭右开，由调用方按「含首含尾」的
     * 自然语言区间换算后再传入。
     */
    public PageResult<MyRechargeOrderVO> pageMyOrders(Long userId, int pageNum, int pageSize, Integer status,
                                                      LocalDateTime startTime, LocalDateTime endTime);
}
