/**
 * 对象存储：上传与异步删除。
 *
 * <p>此处声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：
 * 仅约束模块之间单向依赖、不成环，不强制「外部只能访问模块的根包」。
 * 校验见 {@code ModularityTests}。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.ainovel.module.oss;

import org.springframework.modulith.ApplicationModule;
