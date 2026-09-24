package com.ainovel.common.constant;

/**
 * MQ 常量
 */
public class MqConstant {

    // AI 审核
    public static final String AI_EXCHANGE = "ai.novel.exchange";
    public static final String AI_AUDIT_QUEUE = "ai.novel.audit.queue";
    public static final String AI_AUDIT_ROUTING_KEY = "ai.novel.audit";

    // AI 审核死信
    public static final String AI_DLX_EXCHANGE = "ai.novel.dlx.exchange";
    public static final String AI_AUDIT_DLX_QUEUE = "ai.novel.audit.dlx.queue";
    public static final String AI_AUDIT_DLX_ROUTING_KEY = "ai.novel.audit.dlx";

    // AI 全文审查（阶段 5）：**一章一条消息**，而非「一条消息处理完整本书」。
    // 理由：单条消息处理全书时 ack 粒度为整本书，进程中途退出只能整本重来（且重复扣额度）；
    // 按章投递时崩溃仅丢失当前章，重投由 uk_task_chapter 拦截，
    // 进度即「已处理章数」这一累加值，可直接查询。
    public static final String AI_REVIEW_QUEUE = "ai.novel.review.queue";
    public static final String AI_REVIEW_ROUTING_KEY = "ai.novel.review";

    // 全文审查死信（单章重试耗尽后留痕，同时将任务标记为「已中止」，避免长期停留在"审查中"）
    public static final String AI_REVIEW_DLX_QUEUE = "ai.novel.review.dlx.queue";
    public static final String AI_REVIEW_DLX_ROUTING_KEY = "ai.novel.review.dlx";

    // 搜索同步（小说变更 -> 同步 Elasticsearch）
    public static final String SEARCH_EXCHANGE = "search.novel.exchange";
    public static final String SEARCH_SYNC_QUEUE = "search.novel.sync.queue";
    public static final String SEARCH_SYNC_ROUTING_KEY = "search.novel.sync";

    // 章节块同步（章节正文变更 -> 重建这一章的向量块索引）
    //
    // 独立队列而不并入上述 SEARCH_SYNC 的原因：两者粒度相差一个量级，
    // 作品同步为「一本书一条」，块同步为「一章一条」（一本书几百上千章）。
    // 混用同一队列时，单章重建会被整本重灌阻塞；
    // 且消息体需增加一个可空字段，消费端只能依据该字段是否有值判断本次同步类型。
    public static final String SEARCH_CHUNK_QUEUE = "search.chapter.chunk.queue";
    public static final String SEARCH_CHUNK_ROUTING_KEY = "search.chapter.chunk";

    // 章节块同步死信
    public static final String SEARCH_CHUNK_DLX_QUEUE = "search.chapter.chunk.dlx.queue";
    public static final String SEARCH_CHUNK_DLX_ROUTING_KEY = "search.chapter.chunk.dlx";

    // 搜索同步死信（重试耗尽后留痕；由定时对账按 DB 兜底修复）
    public static final String SEARCH_DLX_EXCHANGE = "search.novel.dlx.exchange";
    public static final String SEARCH_SYNC_DLX_QUEUE = "search.novel.sync.dlx.queue";
    public static final String SEARCH_SYNC_DLX_ROUTING_KEY = "search.novel.sync.dlx";

    // OSS 删除（封面对象异步删除，跨系统最终一致）
    public static final String OSS_EXCHANGE = "oss.delete.exchange";
    public static final String OSS_DELETE_QUEUE = "oss.delete.queue";
    public static final String OSS_DELETE_ROUTING_KEY = "oss.delete";

    // OSS 删除死信（消费重试耗尽后留痕兜底）
    public static final String OSS_DLX_EXCHANGE = "oss.delete.dlx.exchange";
    public static final String OSS_DELETE_DLX_QUEUE = "oss.delete.dlx.queue";
    public static final String OSS_DELETE_DLX_ROUTING_KEY = "oss.delete.dlx";

    // 充值订单超时关单（延迟队列 TTL + DLX）
    public static final String PAY_DELAY_EXCHANGE = "pay.order.delay.exchange";
    public static final String PAY_DELAY_QUEUE = "pay.order.delay.queue";
    public static final String PAY_DELAY_ROUTING_KEY = "pay.order.delay";
    public static final String PAY_CLOSE_EXCHANGE = "pay.order.close.exchange";
    public static final String PAY_CLOSE_QUEUE = "pay.order.close.queue";
    public static final String PAY_CLOSE_ROUTING_KEY = "pay.order.close";

    // 关单死信（消费重试耗尽后留痕兜底）
    public static final String PAY_CLOSE_DLX_EXCHANGE = "pay.order.close.dlx.exchange";
    public static final String PAY_CLOSE_DLX_QUEUE = "pay.order.close.dlx.queue";
    public static final String PAY_CLOSE_DLX_ROUTING_KEY = "pay.order.close.dlx";

    /** 订单超时关闭延时（毫秒）：15 分钟，与 PayMqConfig 队列 TTL 保持一致 */
    public static final long PAY_ORDER_CLOSE_DELAY_MS = 15 * 60 * 1000L;

    // 阅读上报（novel 写完自己的阅读量后，通知 rank 加热度）
    public static final String RANK_READ_EXCHANGE = "rank.read.exchange";
    public static final String RANK_READ_QUEUE = "rank.read.queue";
    public static final String RANK_READ_ROUTING_KEY = "rank.read";

    // 阅读上报死信（消费失败后留痕；热度是派生指标，不重投）
    public static final String RANK_READ_DLX_EXCHANGE = "rank.read.dlx.exchange";
    public static final String RANK_READ_DLX_QUEUE = "rank.read.dlx.queue";
    public static final String RANK_READ_DLX_ROUTING_KEY = "rank.read.dlx";

    private MqConstant() {
    }
}
