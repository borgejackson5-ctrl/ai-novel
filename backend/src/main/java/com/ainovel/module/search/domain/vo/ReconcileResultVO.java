package com.ainovel.module.search.domain.vo;

import lombok.Data;

/**
 * 搜索索引对账结果
 *
 * <p>「一致」仅表示本次比对时无漂移，不代表后续不会漂移，因此该结果是可观测指标，
 * 而非一次性的验收结论。正常情况下应长期保持 consistent=true、修复数为 0。
 */
@Data
public class ReconcileResultVO {

    /** DB 侧「应当被索引」的作品数 */
    private Integer expectedCount;

    /** 索引里实际存在的文档数 */
    private Integer indexedCount;

    /** 该有却没有的（消息丢了 / 消费者重试耗尽转死信） */
    private Integer missingCount;

    /** 不该有却还在的（下架、审核驳回、或作品被删但消息没到） */
    private Integer staleCount;

    /** 实际补写成功条数 */
    private Integer repairedCount;

    /** 实际移除条数 */
    private Integer removedCount;

    /** 比对结果是否一致（修复前判定；有差异就说明可靠性链路漏过一次） */
    private Boolean consistent;

    /**
     * 是否因安全阀跳过修复。
     *
     * <p>触发条件为「DB 侧可见作品为 0」或「被判失效的比例超过一半」：
     * 这两种情况更可能是查询异常（连接了错误的库、条件错误），而非索引确实有误。
     * 此时宁可保留脏数据等待人工确认，也不能将索引清空。
     */
    private Boolean skipped;

    private Long costMs;
}
