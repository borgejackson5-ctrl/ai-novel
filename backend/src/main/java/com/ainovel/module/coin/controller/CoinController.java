package com.ainovel.module.coin.controller;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.domain.ResponseDTO;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.coin.domain.form.ChargeForm;
import com.ainovel.module.coin.domain.form.PayNotifyForm;
import com.ainovel.module.coin.domain.vo.MyRechargeOrderVO;
import com.ainovel.module.coin.domain.vo.RechargeVO;
import com.ainovel.module.coin.service.CoinService;
import com.ainovel.module.coin.service.PayNotifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import com.ainovel.common.annotation.Idempotent;

/**
 * 虚拟币接口
 *
 * <p>安全约束：充值仅可通过「创建订单 → 模拟收银台支付」链路完成，
 * 不提供直接加币的接口（直接加币存在资损风险）。
 */
@Tag(name = "虚拟币")
@RestController
@RequestMapping("/coin")
@RequiredArgsConstructor
public class CoinController {

    private final CoinService coinService;

    private final PayNotifyService payNotifyService;

    @Operation(summary = "查询余额")
    @GetMapping("/balance")
    public ResponseDTO<Integer> balance() {
        return ResponseDTO.ok(coinService.getBalance(LoginUserUtil.getUserId()));
    }

    @Operation(summary = "创建充值订单（存在待支付订单时复用，返回订单号与金额）")
    @PostMapping("/recharge")
    @Idempotent
    public ResponseDTO<RechargeVO> recharge(@Valid @RequestBody ChargeForm form) {
        return ResponseDTO.ok(coinService.createRecharge(LoginUserUtil.getUserId(), form.getAmount()));
    }

    @Operation(summary = "模拟收银台支付（服务端完成，幂等入账）")
    @PostMapping("/pay/mock/{orderNo}")
    @Idempotent
    public ResponseDTO<Void> mockPay(@PathVariable String orderNo) {
        coinService.mockPay(LoginUserUtil.getUserId(), orderNo);
        return ResponseDTO.ok();
    }

    @Operation(summary = "我的充值订单分页（status 可选：0 待支付 / 1 已支付 / 2 已取消；日期区间含首含尾）")
    @GetMapping("/orders")
    public ResponseDTO<PageResult<MyRechargeOrderVO>> myOrders(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        // 用户选择的是日期，查询使用左闭右开区间；结束日需包含当天，故 +1 天后取当天起点
        LocalDateTime startTime = startDate == null ? null : startDate.atStartOfDay();
        LocalDateTime endTime = endDate == null ? null : endDate.plusDays(1).atStartOfDay();
        return ResponseDTO.ok(coinService.pageMyOrders(
                LoginUserUtil.getUserId(), pageNum, pageSize, status, startTime, endTime));
    }

    @Operation(summary = "取消充值订单（待支付 -> 已取消，幂等）")
    @PostMapping("/order/{orderNo}/cancel")
    @Idempotent
    public ResponseDTO<Void> cancel(@PathVariable String orderNo) {
        coinService.cancelOrder(LoginUserUtil.getUserId(), orderNo);
        return ResponseDTO.ok();
    }

    @Operation(summary = "支付网关回调（验签入账，第三方调用，无需登录态）")
    @PostMapping("/pay/notify")
    public ResponseDTO<Void> payNotify(@Valid @RequestBody PayNotifyForm form) {
        payNotifyService.handleNotify(form);
        return ResponseDTO.ok();
    }
}
