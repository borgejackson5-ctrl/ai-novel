package com.ainovel.module.coin.service.impl;

import cn.hutool.core.util.IdUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.enums.CoinTypeEnum;
import com.ainovel.common.enums.OrderStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.PaySignUtil;
import com.ainovel.module.coin.dao.CoinLogMapper;
import com.ainovel.module.coin.dao.RechargeOrderMapper;
import com.ainovel.module.coin.domain.entity.CoinLog;
import com.ainovel.module.coin.domain.entity.RechargeOrder;
import com.ainovel.module.coin.domain.form.PayNotifyForm;
import com.ainovel.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import com.ainovel.module.coin.service.PayNotifyService;

/**
 * 支付网关回调服务：验签 + 防重放 + 幂等入账
 *
 * <p>真实第三方支付（支付宝/微信）回调与本服务的「模拟收银台」共用本方法，差异仅在于
 * 调用发起方：
 * <ul>
 *   <li>真实回调：第三方网关 POST /coin/pay/notify，无登录态，签名即鉴权；</li>
 *   <li>模拟收银台：{@link CoinService#mockPay} 校验归属后，服务端扮演网关生成签名回调。</li>
 * </ul>
 * 密钥 {@code pay.notify-secret} 仅存于后端（env / gitignored 的 application-local.yaml），
 * 不下发至浏览器，前端无法自行构造合法签名，可避免直调入账造成的资损。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayNotifyServiceImpl implements PayNotifyService {

    /** 回调时间戳新鲜度窗口：5 分钟 */
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;
    private static final String NONCE_KEY = "pay:nonce:";

    private final UserService userService;

    private final CoinLogMapper coinLogMapper;

    private final RechargeOrderMapper rechargeOrderMapper;

    private final StringRedisTemplate stringRedisTemplate;

    /** 回调验签密钥：由 env / application-local.yaml 注入，生产环境需覆盖 */
    @Value("${pay.notify-secret:}")
    private String payNotifySecret;

    /**
     * 处理支付回调：验签 → 时间戳新鲜度 → nonce 防重放 → 金额校验 → 幂等入账。
     *
     * <p>入账原子性：{@code markPaid}（status 0→1 的原子 UPDATE）+ 加币 + 记流水同事务，
     * 并发重复回调时只有一次返回 1，保证「同一订单只入账一次」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleNotify(PayNotifyForm form) {
        // 1. 验签（常量时间比较）
        if (!PaySignUtil.verify(form.getOrderNo(), form.getAmount(), form.getTimestamp(),
                form.getNonce(), form.getSign(), payNotifySecret)) {
            throw new BusinessException(ErrorCode.SIGN_ERROR);
        }
        // 2. 时间戳新鲜度（防重放历史回调）
        if (Math.abs(System.currentTimeMillis() - form.getTimestamp()) > TIMEOUT_MS) {
            throw new BusinessException(ErrorCode.SIGN_TIMEOUT);
        }
        // 3. nonce 一次性（防同一回调被重复消费）
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(NONCE_KEY + form.getNonce(), "1", Duration.ofMillis(TIMEOUT_MS));
        if (first == null || !first) {
            throw new BusinessException(ErrorCode.PAY_REPLAY);
        }
        // 4. 查单 + 金额校验（金额已参与签名，此处再校验一次）
        RechargeOrder order = rechargeOrderMapper.selectByOrderNo(form.getOrderNo());
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!order.getCoinAmount().equals(form.getAmount())) {
            throw new BusinessException(ErrorCode.SIGN_ERROR, "支付金额与订单不符");
        }
        // 5. 幂等入账
        int updated = rechargeOrderMapper.markPaid(form.getOrderNo());
        if (updated == 0) {
            // 已支付 → 幂等返回；已取消（超时关单）→ 明确拒绝
            RechargeOrder current = rechargeOrderMapper.selectByOrderNo(form.getOrderNo());
            if (current != null && current.getStatus() == OrderStatusEnum.CANCELED.getCode()) {
                throw new BusinessException(ErrorCode.ORDER_CLOSED);
            }
            return;
        }
        // 6. 加币 + 记流水（同一事务）
        userService.addCoin(order.getUserId(), order.getCoinAmount());
        saveLog(order.getUserId(), order.getCoinAmount(), order.getId());
    }

    /**
     * 构造一条签名完备的回调（供「模拟收银台」扮演第三方网关用）。
     */
    public PayNotifyForm buildNotifyForm(String orderNo, int amount) {
        long timestamp = System.currentTimeMillis();
        String nonce = IdUtil.fastSimpleUUID();
        PayNotifyForm form = new PayNotifyForm();
        form.setOrderNo(orderNo);
        form.setAmount(amount);
        form.setTimestamp(timestamp);
        form.setNonce(nonce);
        form.setSign(PaySignUtil.sign(orderNo, amount, timestamp, nonce, payNotifySecret));
        return form;
    }

    private void saveLog(Long userId, int changeAmount, Long bizId) {
        CoinLog log = new CoinLog();
        log.setUserId(userId);
        log.setChangeAmount(changeAmount);
        log.setType(CoinTypeEnum.CHARGE.getCode());
        log.setBizId(bizId);
        log.setRemark("充值");
        coinLogMapper.insert(log);
    }
}
