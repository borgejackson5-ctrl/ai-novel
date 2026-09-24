package com.ainovel.module.reader.domain.form;

import lombok.Data;

/**
 * 保存阅读偏好请求（字段均可选，未传的保留 DB 默认值）
 */
@Data
public class PreferenceForm {

    private Integer fontSize;

    private String fontFamily;

    private Double lineHeight;

    private Integer columnWidth;

    private String theme;

    private Double brightness;

    private String mode;

    private Integer autoSpeed;
}
