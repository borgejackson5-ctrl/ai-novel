package com.ainovel.module.novel.spi;

import java.util.Collection;
import java.util.Set;

/**
 * 付费情况探测端口（由 subscribe 模块实现）。
 *
 * <p>用于「作者能否删除/下架作品」这类门槛判断：已有读者为其付费的作品不允许直接删除。
 * 判定依据属于订阅域，novel 仅消费结论。
 */
public interface NovelPurchaseProbe {

    /** 是否有读者为这本书付过费。 */
    boolean hasPaidReader(Long novelId);

    /** 批量判断（列表页用，一次 IN 查询，避免逐本回查订单表）。 */
    Set<Long> paidNovelIds(Collection<Long> novelIds);
}
