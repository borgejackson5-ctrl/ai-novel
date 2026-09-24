package com.ainovel.module.novel.spi;

/**
 * 章节可读性判定端口（由 subscribe 模块实现）。
 *
 * <p>「本章是否付费、该用户是否已解锁」属于订阅域的规则，novel 仅需要一个布尔结论。
 * 用同步调用而不是事件：读者点开正文时必须当场拿到答案。
 */
public interface ChapterAccessChecker {

    /**
     * 判断某用户能否读某章。
     *
     * @return true = 可以读（免费章，或已解锁本章，或已解锁整本）
     */
    boolean canRead(Long userId, Long novelId, Long chapterId);
}
