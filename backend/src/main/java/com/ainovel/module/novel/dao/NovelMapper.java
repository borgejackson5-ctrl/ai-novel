package com.ainovel.module.novel.dao;

import com.ainovel.module.novel.domain.entity.Novel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface NovelMapper extends BaseMapper<Novel> {

    /**
     * 阅读量原子自增
     */
    @Update("UPDATE t_novel SET read_count = read_count + 1 WHERE id = #{id} AND is_deleted = 0")
    int incrReadCount(@Param("id") Long id);

    /**
     * 点赞量原子自增
     */
    @Update("UPDATE t_novel SET like_count = like_count + 1 WHERE id = #{id} AND is_deleted = 0")
    int incrLikeCount(@Param("id") Long id);

    /**
     * 点赞量原子自减（取消点赞）；like_count > 0 兜底防负数
     */
    @Update("UPDATE t_novel SET like_count = like_count - 1 WHERE id = #{id} AND is_deleted = 0 AND like_count > 0")
    int decrLikeCount(@Param("id") Long id);

    /**
     * 详情请求的轻量回查：一次主键查询同时满足三项：
     * <ul>
     *   <li>实时计数 read_count/like_count（详情缓存不含高频计数，避免读到 30 分钟前的脏值）；</li>
     *   <li>归属判定所需的 user_id；</li>
     *   <li>可见性判定所需的 status/audit_status。</li>
     * </ul>
     * 同时起到「存在性」判定作用：逻辑删除后返回 null，详情接口据此立即 404，无需等待缓存过期。
     */
    @Select("""
            SELECT read_count, like_count, user_id, status, audit_status
            FROM t_novel WHERE id = #{id} AND is_deleted = 0
            """)
    Novel selectCounts(@Param("id") Long id);

    /**
     * 收藏榜：按收藏数倒序取 Top N 的作品 ID。
     *
     * <p>仅返回 ID 而非整行：榜单最终需按 ZSet 顺序拼装，取 ID 列表后再批量回查更简单，
     * 同时避免 join 产生的重复列覆盖实体字段。同分时按 id 倒序，保证顺序稳定。
     *
     * <p><b>可见性条件由调用方从 {@code NovelVisibility} 传入，不在此处硬编码 1 / (1,3)</b>：
     * 手写 SQL 中的枚举值变更不会产生编译错误，只会静默漂移出「搜得到、点进去 404」。
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
