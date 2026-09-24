package com.ainovel.module.ai.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户 AI 配置实体（每用户一行：自带 Key + 平台免费额度）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_ai_config")
public class UserAiConfig extends BaseEntity {

    private Long userId;

    /** 用户自带 API Key（为空表示使用平台 Key，走免费额度） */
    private String ownKey;

    /**
     * 自带 Key 配套的服务地址（为空表示沿用平台的）。
     *
     * <p>填入其他服务商的 Key 必须同时填写对应地址：若仅替换 Key 而不替换 endpoint，
     * 请求会发送到平台服务地址，用户得到的是 401，而非可理解的「不支持」提示。
     */
    private String baseUrl;

    /** 自带 Key 配套的模型名（为空表示沿用平台的） */
    private String model;

    /** 已消耗的平台免费额度 */
    private Integer usedCount;

    /** 平台免费额度上限 */
    private Integer quotaLimit;
}
