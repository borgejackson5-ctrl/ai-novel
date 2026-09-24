package com.ainovel.module.rank.service;

import com.ainovel.common.enums.RankTypeEnum;
import com.ainovel.module.novel.domain.vo.NovelVO;
import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 热门榜单服务：Caffeine(L1) + Redis ZSet(L2) + MySQL(L3) 三级缓存 + Lua 限流 + 缓存三防
 *
 * <p>缓存链路：Caffeine(L1) -> Redis ZSet(L2) -> MySQL
 * <p>缓存一致性：写操作更新 DB 后同步维护 ZSet 分数
 *
 * <p>缓存三项异常防御：
 * <ul>
 *   <li><b>穿透</b>：DB/ZSet 都为空时设置 Redis 空标记（随机 TTL），命中直接返回空，不重复回源 DB</li>
 *   <li><b>击穿</b>：热点缓存失效回源 DB 时用 Redisson 互斥锁，仅单线程重建，其余降级/等待</li>
 *   <li><b>雪崩</b>：L1 本地缓存用 Caffeine Expiry 随机过期（30~40s），空标记 TTL 也随机，避免批量同时过期</li>
 * </ul>
 */
public interface RankService {

    @PostConstruct
    public void init();

    /**
     * 获取榜单。四种榜共用同一条链路，只有回源 SQL 的排序口径不同。
     */
    public List<NovelVO> rank(RankTypeEnum type);

    /** 热门榜（兼容旧接口） */
    public List<NovelVO> hotRank();

    /**
     * 热度 +1：把一次阅读反映到热门榜的 ZSet 上。
     *
     * <p>阅读量本身由 novel 模块写入其自身表（{@code NovelService#recordRead}），
     * 该处已完成用户级去重；此处收到的是「一条已确认需计数的阅读」。
     */
    public void incrHot(Long novelId);
}
