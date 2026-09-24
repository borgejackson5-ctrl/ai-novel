package com.ainovel.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要记入管理端操作日志（审计追溯）的写操作。
 *
 * <p>由 {@code com.ainovel.module.admin.aop.AdminLogAspect} 环绕处理：成功与失败均写入数据库，
 * 失败时额外记录异常信息后原样抛出，不改变原有异常语义。
 *
 * <p>建议标注在 Controller 方法上（而非 Service）：切面位于业务事务之外，
 * 事务已提交或回滚后才写日志，不会出现「业务已回滚而日志留存」的脏记录。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AdminLogRecord {

    /** 模块：AUDIT / USER / ORDER / FEEDBACK / NOVEL / AI / IMPORT */
    String module();

    /** 动作：PASS / REJECT / IMPORT / STATUS / HANDLE / RESET / SAVE / DELETE */
    String action();

    /** 目标类型：NOVEL / CHAPTER / USER / FEEDBACK / AI_QUOTA，无目标可留空 */
    String targetType() default "";

    /** 操作摘要（人可读，列表直接展示），如「审核通过作品」 */
    String summary();

    /** 目标 ID 取自第几个入参（从 0 开始）；-1 表示该操作没有单一目标 ID */
    int targetIdIndex() default 0;

    /**
     * 补充详情：SpEL 表达式，入参以 {@code #p0}、{@code #p1} … 引用。
     *
     * <p>例如审核拒绝：{@code detail = "#p1.reason"}。留空则不记录详情。
     */
    String detail() default "";
}
