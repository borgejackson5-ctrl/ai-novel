package com.ainovel.common.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 阅读上报消息：某本作品的阅读量增加 1，通知榜单更新热度。
 *
 * <p>仅携带 novelId，不携带用户信息：去重（同一用户 24h 内仅计一次）已在发布方
 * （{@code NovelService.recordRead}）完成，消费方只需获知「哪本书 +1」。
 * 因此消费端无需回查 DB，也不会因回查读到旧数据而产生计算错误。
 */
@Data
public class NovelReadMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long novelId;
}
