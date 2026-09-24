package com.ainovel.common.message;

import lombok.Data;

/**
 * 小说搜索同步消息（DB 变更 -> Elasticsearch）
 */
@Data
public class SearchSyncMessage {

    /** 小说 ID */
    private Long novelId;

    /**
     * 操作类型：
     * <ul>
     *   <li>UPSERT：新增/编辑/上下架，按 MySQL 最新数据覆盖写入 ES</li>
     *   <li>DELETE：删除，从 ES 移除文档</li>
     * </ul>
     */
    private String operation;
}
