package com.ainovel.module.admin.domain.entity;

import com.ainovel.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 管理端操作日志实体（审计追溯）。
 *
 * <p>记录「谁在何时对哪条内容执行了什么操作」：管理员身份、模块动作、目标对象、
 * 操作摘要与详情、来源 IP、是否成功、耗时。写操作失败同样持久化，便于事后排查。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_admin_log")
public class AdminLog extends BaseEntity {

    /** 操作人用户 ID */
    private Long adminId;

    /** 操作人账号（写入时快照，避免用户改名或删号后无法追溯） */
    private String adminName;

    /** 模块：AUDIT / USER / ORDER / FEEDBACK / NOVEL / AI / IMPORT */
    private String module;

    /** 动作：PASS / REJECT / IMPORT / STATUS / HANDLE / RESET / SAVE / DELETE */
    private String action;

    /** 目标类型：NOVEL / CHAPTER / USER / FEEDBACK / AI_QUOTA */
    private String targetType;

    /** 目标主键 */
    private Long targetId;

    /** 操作摘要（人可读） */
    private String summary;

    /** 补充详情（拒绝理由 / 导入结果 / 变更值等） */
    private String detail;

    /** 操作来源 IP */
    private String ip;

    /** 是否成功（0 失败 / 1 成功） */
    private Integer success;

    /** 失败原因 */
    private String errorMsg;

    /** 耗时毫秒 */
    private Long costMs;
}
