package com.ainovel.module.reader.domain.vo;

import lombok.Data;

/**
 * 阅读偏好视图
 */
@Data
public class PreferenceVO {

    private Integer fontSize;

    private String fontFamily;

    private Double lineHeight;

    private Integer columnWidth;

    private String theme;

    private Double brightness;

    private String mode;

    private Integer autoSpeed;
}
