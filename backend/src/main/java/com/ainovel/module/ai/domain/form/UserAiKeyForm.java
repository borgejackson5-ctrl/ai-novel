package com.ainovel.module.ai.domain.form;

import lombok.Data;

/**
 * 用户自带 AI 配置保存表单（Key + 配套的 endpoint / 模型）
 */
@Data
public class UserAiKeyForm {

    /** 脱敏值(含****)表示未修改；空串表示清除自带 Key；否则为新 Key */
    private String apiKey;

    /** 自带 Key 对应的服务地址（OpenAI 兼容）；留空表示沿用平台配置 */
    private String baseUrl;

    /** 自带 Key 对应的模型名；留空表示沿用平台配置 */
    private String model;
}
