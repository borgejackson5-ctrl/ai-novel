/**
 * 运行指标（进程内耗时统计）。
 *
 * <p>这里声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：
 * 仅约束「模块之间单向依赖、不成环」，不强制「外部只能访问模块的根包」。
 * 校验见 {@code ModularityTests}。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.ainovel.module.monitor;

import org.springframework.modulith.ApplicationModule;
