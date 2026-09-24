package com.ainovel.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 集成测试用的数据夹具。
 *
 * <p>直接用 SQL 插入而不调用 Service：Service 中大量方法第一步即
 * {@code LoginUserUtil.getUserId()}，测试线程内没有 Sa-Token 上下文，取不到用户。
 * 夹具只负责准备初始数据，被测链路仍从 HTTP 进入。
 *
 * <p>不复用 init.sql 中种子数据的原因：其中只有角色与分类（7 条），没有作品与章节。
 * 涉及「读者能否看到」的用例都需要一本已过审且已上架的书，
 * 而走真实发布接口生成的书处于「待审 + 下架」状态（见 NovelServiceImpl.publish），
 * 读者侧不可见，那是另一条用例的职责。
 */
public final class TestData {

    private TestData() {
    }

    /**
     * 夹具主键。使用远离真实数据的号段，避免与 MyBatis-Plus 的雪花 id 冲突，
     * 也避免与 1..1000 这类容易被人工误认为「系统数据」的小数字冲突。
     */
    private static final AtomicLong SEQ = new AtomicLong(9_000_000_000_000_000_000L);

    /** 已过审且已上架的书：首章免费，次章 5 币（付费墙用例使用） */
    public record PublishedBook(long novelId, long authorId, long freeChapterId, long paidChapterId,
                                String title, String intro) {
    }

    /** 按用户名查询 id（演示账号由 DataInitializer 在启动时写入，id 为生成值，只能查询） */
    public static long userId(JdbcTemplate jdbc, String username) {
        Long id = jdbc.queryForObject("SELECT id FROM t_user WHERE username = ? AND is_deleted = 0",
                Long.class, username);
        if (id == null) {
            throw new IllegalStateException("演示账号不存在：" + username + "（DataInitializer 应该已经种下）");
        }
        return id;
    }

    /**
     * 造一本读者可见的书。
     *
     * @param categoryId 使用 init.sql 中种下的分类（1 为「玄幻」，见 t_category 的 INSERT）
     */
    public static PublishedBook publishedBook(JdbcTemplate jdbc, String title, String intro, long authorId) {
        long novelId = SEQ.incrementAndGet();
        long freeChapterId = SEQ.incrementAndGet();
        long paidChapterId = SEQ.incrementAndGet();

        // status=1 上架、audit_status=1 已过审：这两项是读者可见性的开关
        jdbc.update("""
                        INSERT INTO t_novel (id, title, category_id, intro, tags, author, user_id,
                                             total_chapters, word_count, coin_price, read_count, like_count,
                                             status, audit_status, serial_status, create_time, update_time, is_deleted)
                        VALUES (?, ?, 1, ?, ?, ?, ?, 2, ?, 3, 0, 0, 1, 1, 0, NOW(), NOW(), 0)
                        """,
                novelId, title, intro, "测试", "测试作者", authorId, intro.length() + 20);

        jdbc.update("""
                        INSERT INTO t_chapter (id, novel_id, chapter_no, title, content, word_count, unlock_coin,
                                               audit_status, sort, create_time, update_time, is_deleted)
                        VALUES (?, ?, 1, ?, ?, ?, 0, 1, 0, NOW(), NOW(), 0)
                        """,
                freeChapterId, novelId, "第一章 免费试读", "这是第一章的正文，免费试读用。" + intro, 20);

        jdbc.update("""
                        INSERT INTO t_chapter (id, novel_id, chapter_no, title, content, word_count, unlock_coin,
                                               audit_status, sort, create_time, update_time, is_deleted)
                        VALUES (?, ?, 2, ?, ?, ?, 5, 1, 1, NOW(), NOW(), 0)
                        """,
                paidChapterId, novelId, "第二章 付费章", "这是第二章的正文，需要 5 币解锁。", 20);

        return new PublishedBook(novelId, authorId, freeChapterId, paidChapterId, title, intro);
    }
}
