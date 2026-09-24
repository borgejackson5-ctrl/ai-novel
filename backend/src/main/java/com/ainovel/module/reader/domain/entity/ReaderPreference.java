package com.ainovel.module.reader.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 阅读偏好实体（每用户一行，跨设备跟随）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_reader_preference")
public class ReaderPreference extends BaseEntity {

    /** 用户 ID（唯一，一行一用户） */
    private Long userId;

    /** 字号（px） */
    private Integer fontSize;

    /** 字族：song 宋体 / hei 黑体 / kai 楷体 */
    private String fontFamily;

    private Double lineHeight;

    private Integer columnWidth;

    /** 主题：day 白天 / sepia 护眼 / night 夜间 */
    private String theme;

    /** 亮度（0.3~1） */
    private Double brightness;

    /** 翻页模式：scroll / page */
    private String mode;

    /** 自动阅读速度（px/秒） */
    private Integer autoSpeed;
}
