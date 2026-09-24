package com.ainovel.module.history.dao;

import com.ainovel.module.history.domain.entity.ReadHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReadHistoryMapper extends BaseMapper<ReadHistory> {

    /**
     * 幂等写入一条阅读记录：同一「用户 + 章节」只保留一条，重复阅读只刷新时间与标题。
     *
     * <p>采用「唯一索引 + ON DUPLICATE KEY UPDATE」而非「先查后写」：先查后写在并发下必然漏判
     * （两个请求同时查到 0 条，随后各插一条），除非额外引入分布式锁；将幂等交由数据库唯一约束
     * 实现，只需一次往返、无需加锁，且不会误判。
     *
     * <p>不做流水式追加：展示侧本就按作品去重取最新记录，流水行仅使表持续膨胀，无其他用途
     * （单本作品可达数千章，再乘以重复阅读次数）。
     *
     * <p>依赖唯一索引 {@code uk_user_chapter(user_id, chapter_id)}，见
     * {@code sql/migration_dedup_read_history.sql}。索引缺失时本语句退化成普通插入，
     * 不会报错、只是失去去重效果，因此可以安全地先发代码再补索引。
     */
    @Insert("""
            INSERT INTO t_read_history
                (id, user_id, novel_id, chapter_id, chapter_no, novel_title, chapter_title,
                 create_time, update_time, is_deleted)
            VALUES
                (#{id}, #{userId}, #{novelId}, #{chapterId}, #{chapterNo}, #{novelTitle}, #{chapterTitle},
                 NOW(), NOW(), 0)
            ON DUPLICATE KEY UPDATE
                update_time = NOW(),
                chapter_no = VALUES(chapter_no),
                novel_title = VALUES(novel_title),
                chapter_title = VALUES(chapter_title)
            """)
    int upsertIgnoreDuplicate(ReadHistory history);

    /**
     * 逐章阅读人数（去重 UV），按章序升序。
     *
     * <p>这是作者数据看板的核心数据源：章节阅读人数降幅最大处通常对应读者流失点，
     * 其参考价值高于「总阅读量」。
     *
     * <p>采用 COUNT(DISTINCT user_id) 而非 COUNT(*)：同一用户多次阅读同一章不应计为多名读者。
     */
    @Select("""
            SELECT chapter_no AS chapterNo,
                   MAX(chapter_title) AS chapterTitle,
                   COUNT(DISTINCT user_id) AS readers
            FROM t_read_history
            WHERE novel_id = #{novelId} AND is_deleted = 0
            GROUP BY chapter_no
            ORDER BY chapter_no
            """)
    List<Map<String, Object>> selectChapterReaders(@Param("novelId") Long novelId);

    /**
     * 全书的去重阅读人数（UV）。
     */
    @Select("""
            SELECT COUNT(DISTINCT user_id) FROM t_read_history
            WHERE novel_id = #{novelId} AND is_deleted = 0
            """)
    Long selectDistinctReaders(@Param("novelId") Long novelId);
}
