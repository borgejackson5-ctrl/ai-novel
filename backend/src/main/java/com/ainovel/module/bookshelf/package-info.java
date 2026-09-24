/**
 * 书架：收藏与取消。
 *
 * <p>声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：
 * 仅校验「模块之间单向依赖、无环」这一条，不强制「外部只能访问模块的根包」。
 * 校验见 {@code ModularityTests}。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.ainovel.module.bookshelf;

import org.springframework.modulith.ApplicationModule;
