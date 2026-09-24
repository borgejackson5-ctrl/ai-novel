package com.ainovel.module.subscribe.spi;

import com.ainovel.common.enums.OrderStatusEnum;
import com.ainovel.module.novel.spi.NovelPurchaseProbe;
import com.ainovel.module.subscribe.dao.SubscribeOrderMapper;
import com.ainovel.module.subscribe.domain.entity.SubscribeOrder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link NovelPurchaseProbe} 的实现：仅查询订阅订单表，用于判定该作品是否存在付费读者。
 *
 * <p>独立于 {@code SubscribeServiceImpl} 实现的原因：后者注入了 {@code NovelService}，
 * 而 novel 侧持有本端口，启动时将形成
 * {@code novelService → 本实现 → novelService} 的 bean 环。拆分为独立实现类、
 * 且仅依赖订单表即可消除该环（模块依赖方向不变：subscribe → novel）。
 */
@Component
@RequiredArgsConstructor
public class NovelPurchaseProbeImpl implements NovelPurchaseProbe {

    private final SubscribeOrderMapper orderMapper;

    @Override
    public boolean hasPaidReader(Long novelId) {
        return orderMapper.selectCount(new LambdaQueryWrapper<SubscribeOrder>()
                .eq(SubscribeOrder::getNovelId, novelId)
                .eq(SubscribeOrder::getStatus, OrderStatusEnum.PAID.getCode())) > 0;
    }

    @Override
    public Set<Long> paidNovelIds(Collection<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) {
            return Set.of();
        }
        // 字符串列投影（配合 QueryWrapper）：只取 novel_id 一列，且不依赖 TableInfo 解析
        return orderMapper.selectList(new QueryWrapper<SubscribeOrder>()
                        .select("novel_id")
                        .in("novel_id", novelIds)
                        .eq("status", OrderStatusEnum.PAID.getCode()))
                .stream().map(SubscribeOrder::getNovelId).collect(Collectors.toSet());
    }
}
