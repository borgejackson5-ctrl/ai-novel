/**
 * 搜索：ES 索引、检索与一致性对账。
 *
 * <p>此处声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：
 * 仅约束模块之间单向依赖、不成环，不强制「外部只能访问模块的根包」。
 * 校验见 {@code ModularityTests}。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.ainovel.module.search;

import org.springframework.modulith.ApplicationModule;
