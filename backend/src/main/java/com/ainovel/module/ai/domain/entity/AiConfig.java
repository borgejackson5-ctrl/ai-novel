package com.ainovel.module.ai.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 配置实体（单行配置，id 固定为 1）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ai_config")
public class AiConfig extends BaseEntity {

    private String baseUrl;
    private String apiKey;
    private String model;
    private Double temperature;
    private Integer mockEnabled;

    /**
     * 非持久化：本次调用扣除的免费字数（null 或 ≤0 表示未扣费）。
     *
     * <p>额度采用「先扣后调」（防止并发超发），因此调用失败时必须按该次扣费归还。
     * 该值用于判断「是否需要归还、归还多少」：自带 Key、管理员、无用户上下文的调用均不扣费，
     * 无需判断是否归还。
     *
     * <p>保存数量而非一个 boolean：额度按字数计算，退款需按原样退回，
     * 不能不论扣费多少均归还 1。
     */
    @TableField(exist = false)
    private Long quotaChargedUnits;
}
