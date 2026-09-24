package com.ainovel.module.novel.dao;

import com.ainovel.module.novel.domain.entity.Chapter;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ChapterMapper extends BaseMapper<Chapter> {

    /**
     * 当前最大章序号（新增章节连载用；无章节时返回 0）
     *
     * <p><b>刻意不过滤 {@code is_deleted}</b>：{@code BaseEntity} 带 {@code @TableLogic}，
     * 删章为逻辑删除，**软删行仍占用唯一索引 {@code uk_novel_no(novel_id, chapter_no)}**。
     * 若此处按 {@code is_deleted = 0} 取最大值，将出现：
     * 删除第 N 章（待审章，软删）→ 再次新增 → 又算得 {@code nextNo = N} → 唯一键冲突 → 抛异常，
     * 且重试三次读到的仍是同一个 max，**该作品此后无法新增章节**。
     *
     * <p>章号因此只增不减（已删除的号不再复用），此为有意设计：
     * 读者侧不会看到章号跳号，作者可继续正常连载。
     */
    @Select("SELECT IFNULL(MAX(chapter_no), 0) FROM t_chapter WHERE novel_id = #{novelId}")
    Integer selectMaxChapterNo(@Param("novelId") Long novelId);

    /**
     * 可见章节聚合统计（仅审核通过/变更待审，即读者可见章节）：
     * 用于章节变更后重算 t_novel 的 total_chapters / word_count / coin_price。
     * 返回 Map：chapterCount / wordCount / paidCoinSum。
     */
    @Select("SELECT COUNT(*) AS chapterCount, " +
            "IFNULL(SUM(word_count), 0) AS wordCount, " +
            "IFNULL(SUM(CASE WHEN unlock_coin > 0 THEN unlock_coin ELSE 0 END), 0) AS paidCoinSum " +
            "FROM t_chapter WHERE novel_id = #{novelId} AND is_deleted = 0 AND audit_status IN (1, 3)")
    Map<String, Object> selectVisibleStats(@Param("novelId") Long novelId);

    /**
     * 批量取「每本书的首章」（管理端审核预览正文节选用）。
     *
     * <p>单次查询解决 N+1：原实现为遍历列表逐本 `selectOne(orderByAsc(chapter_no) LIMIT 1)`，
     * 一页 100 本即产生 100 次额外查询。
     *
     * <p><b>手写 SQL 需自行书写 `is_deleted = 0`</b>：MP 的逻辑删除注入不作用于手写 SQL，
     * 而原 lambda 查询会自动带上该条件，遗漏会使软删章节被当作首章。
     * 首章定义保持一致：该作品下 {@code chapter_no} 最小的章。
     */
    @Select("""
            <script>
            SELECT c.id, c.novel_id, c.content
            FROM t_chapter c
            JOIN (SELECT novel_id, MIN(chapter_no) AS min_no
                    FROM t_chapter
                   WHERE is_deleted = 0
                     AND novel_id IN
                     <foreach collection="novelIds" item="nid" open="(" separator="," close=")">#{nid}</foreach>
                   GROUP BY novel_id) f
              ON f.novel_id = c.novel_id AND f.min_no = c.chapter_no
            WHERE c.is_deleted = 0
            </script>
            """)
    List<Chapter> selectFirstChaptersByNovelIds(@Param("novelIds") List<Long> novelIds);

    /**
     * 物理删除某本书的全部章节（公版书「覆盖导入」重建章节用）
     *
     * <p>必须物理删除：{@code BaseEntity} 带 {@code @TableLogic}，Service 的 remove 为逻辑删除，
     * 残留行仍占用唯一索引 uk_novel_no(novel_id, chapter_no)，随后插入新章节会直接唯一键冲突。
     * 逻辑删除对「重建」这类语义不成立：旧正文不会再被读取，保留旧行无实际作用。
     */
    @Delete("DELETE FROM t_chapter WHERE novel_id = #{novelId}")
    int deleteByNovelId(@Param("novelId") Long novelId);
}
