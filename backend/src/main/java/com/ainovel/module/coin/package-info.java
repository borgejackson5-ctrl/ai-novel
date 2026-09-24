/**
 * 书币：余额、充值下单与支付回调。
 *
 * <p>声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：
 * 仅校验「模块之间单向依赖、无环」这一条，不强制「外部只能访问模块的根包」。
 * 校验见 {@code ModularityTests}。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.ainovel.module.coin;

import org.springframework.modulith.ApplicationModule;
