package com.ainovel.module.admin.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志视图对象
 */
@Data
public class AdminLogVO {

    private Long id;
    private Long adminId;
    private String adminName;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private String summary;
    private String detail;
    private String ip;
    private Integer success;
    private String errorMsg;
    private Long costMs;
    private LocalDateTime createTime;
}
