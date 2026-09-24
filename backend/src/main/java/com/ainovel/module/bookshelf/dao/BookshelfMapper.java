package com.ainovel.module.bookshelf.dao;

import com.ainovel.module.bookshelf.domain.entity.Bookshelf;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BookshelfMapper extends BaseMapper<Bookshelf> {

    /**
     * 移出书架：物理删除（收藏是轻量开关，无需软删留痕，也避免 UNIQUE 被软删行占位）
     */
    @Delete("DELETE FROM t_bookshelf WHERE user_id = #{userId} AND novel_id = #{novelId}")
    int deleteByUserAndNovel(@Param("userId") Long userId, @Param("novelId") Long novelId);

    /**
     * 书架分页（最近收藏在前），关键词按书名模糊匹配。
     *
     * <p>JOIN 仅用于「按书名筛选」和分页计数，**不参与结果装配**：作品是否已被逻辑删除
     * 仍由 {@code selectBatchIds}（自带 @TableLogic 过滤）判定，失效条目仍渲染为占位卡片，
     * 避免作品被删除后从列表中消失。因此本查询有意不加
     * {@code n.is_deleted = 0}。
     *
     * <p>手写 SQL 不经过 MP 的逻辑删除注入，列名需与库表一致：库中为 {@code is_deleted}
     * （实体字段名为 deleted，见 {@code BaseEntity}）。
     */
    @Select("""
            <script>
            SELECT b.* FROM t_bookshelf b
            JOIN t_novel n ON n.id = b.novel_id
            WHERE b.is_deleted = 0 AND b.user_id = #{userId}
            <if test="kw != null and kw != ''">
              AND n.title LIKE CONCAT('%', #{kw}, '%')
            </if>
            ORDER BY b.id DESC
            </script>
            """)
    IPage<Bookshelf> pageByUser(IPage<Bookshelf> page,
                                @Param("userId") Long userId,
                                @Param("kw") String kw);

    /**
     * 某作品的收藏数（作者数据看板用）。
     *
     * <p>实时统计而非为 t_novel 增加冗余计数列：看板为低频只读场景，
     * 无需为此维护一个可能与真实值不一致的计数器。
     */
    @Select("SELECT COUNT(*) FROM t_bookshelf WHERE novel_id = #{novelId}")
    Long countByNovel(@Param("novelId") Long novelId);

    /**
     * 收藏数最多的作品 id（榜单回源用），只回 ID 不回整行。
     *
     * <p>本查询 join 了 {@code t_novel}（因需按作品可见性过滤），但**收藏数据位于书架一侧**，
     * 因此归属本模块：若移入作品侧的查询，等于让 novel 读取书架表，
     * 模块依赖将形成环。
     *
     * <p>可见性条件由调用方从 {@code NovelVisibility} 传入，不在 SQL 中写死枚举值：
     * 手写 SQL 中的取值变更不会编译报错，只会静默漂移为「可搜索、访问返回 404」。
     */
    @Select("""
            <script>
            SELECT n.id FROM t_novel n
            JOIN t_bookshelf b ON b.novel_id = n.id AND b.is_deleted = 0
            WHERE n.is_deleted = 0
              AND n.status = #{status}
              AND n.audit_status IN
              <foreach collection="auditStatuses" item="s" open="(" separator="," close=")">#{s}</foreach>
            GROUP BY n.id
            ORDER BY COUNT(*) DESC, n.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<Long> selectIdByCollectDesc(@Param("limit") int limit,
                                     @Param("status") int status,
                                     @Param("auditStatuses") List<Integer> auditStatuses);
}
