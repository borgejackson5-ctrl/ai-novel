/**
 * 作品与章节：发布、连载、审核状态机、作者看板。
 *
 * <p>这里声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：
 * 只守「模块之间单向依赖、不成环」这一条，不强制「外部只能访问模块的根包」。
 * 校验见 {@code ModularityTests}。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.ainovel.module.novel;

import org.springframework.modulith.ApplicationModule;
