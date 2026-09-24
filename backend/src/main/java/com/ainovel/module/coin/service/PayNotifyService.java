package com.ainovel.module.coin.service;

import com.ainovel.module.coin.domain.form.PayNotifyForm;
import org.springframework.transaction.annotation.Transactional;

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
public interface PayNotifyService {

    /**
     * 处理支付回调：验签 → 时间戳新鲜度 → nonce 防重放 → 金额校验 → 幂等入账。
     *
     * <p>入账原子性：{@code markPaid}（status 0→1 的原子 UPDATE）+ 加币 + 记流水同事务，
     * 并发重复回调时只有一次返回 1，保证「同一订单只入账一次」。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleNotify(PayNotifyForm form);

    /**
     * 构造一条签名完备的回调（供「模拟收银台」扮演第三方网关用）。
     */
    public PayNotifyForm buildNotifyForm(String orderNo, int amount);
}
