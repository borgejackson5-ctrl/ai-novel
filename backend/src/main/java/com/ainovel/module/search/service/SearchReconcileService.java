package com.ainovel.module.search.service;

import com.ainovel.module.search.domain.vo.ReconcileResultVO;

/**
 * 搜索索引对账：比对 MySQL 与 Elasticsearch，消除两者的漂移。
 *
 * <p>该机制是最终一致的最后一道保障。此前几层（事务提交后投递、消费时回查 DB、失败退避重投、
 * 重试耗尽转死信）保证的是正常情况不丢失、异常时可重试；但只要消息可能丢失
 * （broker 故障、消费者重试耗尽进入死信无人处理、有人直接修改数据库），索引必然产生漂移。
 * 对账的价值不在速度，而在于使漂移必然被发现并自动修复。
 *
 * <p>两条约束：
 * <ol>
 *   <li>MySQL 是唯一事实源：始终以 DB 侧结果为准修复索引，不可反向；</li>
 *   <li>对自身判断的怀疑优先于对索引的怀疑：DB 侧查出空结果或失效比例异常高时，
 *       应先停止修复。宁可保留脏数据等待人工确认，也不能因一条错误条件将索引清空。</li>
 * </ol>
 */
public interface SearchReconcileService {

    /**
     * 全量对账并修复。
     *
     * <p>保守策略：先比对，再判定安全阀，最后执行修复。任何一步异常均直接抛出，
     * 不执行「尽力而为」的部分修复：半修复状态比漂移更难排查。
     */
    public ReconcileResultVO reconcile();
}
