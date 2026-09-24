package com.ainovel.module.ai.domain.vo;

import lombok.Data;

/**
 * 用户 AI 配置视图（返回前端，ownKey 已脱敏）。
 */
@Data
public class UserAiConfigVO {

    /** 脱敏后的自带 Key，如 sk-****abcd；无自带 Key 时为空串 */
    private String ownKey;

    /** 是否已配置自带 Key：已配置则不受免费额度限制 */
    private Boolean hasOwnKey;

    /** 今日已用的免费字数（每日重置） */
    private Long usedUnits;

    /** 每日免费字数上限 */
    private Integer quotaLimit;

    /** 今日剩余字数，由服务端计算后下发，前端直接展示 */
    private Long remainingUnits;

    /** 自带 Key 配套的服务地址（未配置为空串） */
    private String baseUrl;

    /** 自带 Key 配套的模型名（未配置为空串） */
    private String model;

    /**
     * 平台当前使用的服务地址与模型。
     *
     * <p>下发给前端作为「留空则沿用本站」的占位提示：用户需了解「不填会走哪个服务」，
     * 才会决定是否自行填写。这两个值本身不含 Key，不属于敏感信息。
     */
    private String platformBaseUrl;

    private String platformModel;
}
