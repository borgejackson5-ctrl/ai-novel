package com.ainovel.module.monitor.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一条慢记录（慢请求或慢 SQL）
 */
@Data
@AllArgsConstructor
public class SlowRecordVO {

    /** 慢请求：HTTP 方法 + 路径模板；慢 SQL：Mapper 方法名 */
    private String name;

    private Long costMs;

    /** 慢 SQL 才有：截断后的 SQL 文本（慢请求为 null） */
    private String detail;

    /** 发生时间（MM-dd HH:mm:ss） */
    private String time;
}
