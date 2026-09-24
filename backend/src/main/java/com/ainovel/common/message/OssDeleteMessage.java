package com.ainovel.common.message;

import lombok.Data;

/**
 * OSS 对象删除消息（封面等对象异步删除，跨系统最终一致）
 *
 * <p>消息必须<b>自包含 url</b>，而非仅携带 novelId：删除小说为逻辑删除（@TableLogic），
 * 消费端事后无法再回查出 coverUrl，因此发送前需将待删除对象的地址固定在消息中。
 */
@Data
public class OssDeleteMessage {

    /** 待删除对象的公开访问 URL（消费端据此解析出 OSS key 后删除） */
    private String url;
}
