package com.ainovel.module.ai.domain.form;

import lombok.Data;

/**
 * AI 配置保存表单
 */
@Data
public class AiConfigForm {

    private String baseUrl;
    private String apiKey;
    private String model;
    private Double temperature;
    private Boolean mockEnabled;
}
