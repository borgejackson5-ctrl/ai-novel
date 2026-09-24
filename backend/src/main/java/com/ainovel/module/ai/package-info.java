/**
 * AI 能力：作品/章节预审、文本生成、AI 搜索增强。
 *
 * <p>这里声明为 {@link org.springframework.modulith.ApplicationModule.Type#OPEN}：
 * 只守「模块之间单向依赖、不成环」这一条，不强制「外部只能访问模块的根包」。
 * 校验见 {@code ModularityTests}。
 */
@ApplicationModule(type = ApplicationModule.Type.OPEN)
package com.ainovel.module.ai;

import org.springframework.modulith.ApplicationModule;
