/**
 * 认证与授权：登录注册、验证码、Sa-Token 权限。
 *
 * <p>这里声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：
 * 仅约束「模块之间单向依赖、不成环」，不强制「外部只能访问模块的根包」。
 * 校验见 {@code ModularityTests}。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.ainovel.module.auth;

import org.springframework.modulith.ApplicationModule;
