package com.ainovel.module.admin.aop;

import com.ainovel.common.annotation.AdminLogRecord;
import com.ainovel.common.util.IpUtil;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.admin.dao.AdminLogMapper;
import com.ainovel.module.admin.domain.entity.AdminLog;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理端操作日志切面：记录「谁在何时对哪条内容执行了什么操作」。
 *
 * <p>成功与失败均记录（失败附带原因），异常照常抛出，不影响业务链路。
 * 日志写入失败仅记录 warn，不因审计逻辑导致业务操作失败。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AdminLogAspect {

    /** SpEL 解析结果缓存：注解表达式是常量，避免每次调用重复解析 */
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentHashMap<String, Expression> expressionCache = new ConcurrentHashMap<>();

    private final AdminLogMapper adminLogMapper;

    private final UserService userService;

    @Around("@annotation(record)")
    public Object around(ProceedingJoinPoint pjp, AdminLogRecord record) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            Object result = pjp.proceed();
            write(pjp, record, start, true, null);
            return result;
        } catch (Throwable t) {
            write(pjp, record, start, false, t.getMessage());
            throw t;
        }
    }

    private void write(ProceedingJoinPoint pjp, AdminLogRecord record, long start,
                       boolean success, String errorMsg) {
        try {
            AdminLog entity = new AdminLog();
            Long adminId = LoginUserUtil.getUserIdOrNull();
            entity.setAdminId(adminId);
            entity.setAdminName(resolveAdminName(adminId));
            entity.setModule(record.module());
            entity.setAction(record.action());
            entity.setTargetType(record.targetType().isBlank() ? null : record.targetType());
            entity.setTargetId(resolveTargetId(pjp, record));
            entity.setSummary(record.summary());
            entity.setDetail(resolveDetail(pjp, record));
            entity.setIp(IpUtil.getClientIp());
            entity.setSuccess(success ? 1 : 0);
            entity.setErrorMsg(truncate(errorMsg, 500));
            entity.setCostMs(System.currentTimeMillis() - start);
            adminLogMapper.insert(entity);
        } catch (Exception e) {
            // 审计写入失败不得影响业务操作
            log.warn("写入操作日志失败: module={} action={}", record.module(), record.action(), e);
        }
    }

    private String resolveAdminName(Long adminId) {
        if (adminId == null) {
            return null;
        }
        try {
            User user = userService.getUser(adminId);
            return user == null ? null : user.getUsername();
        } catch (Exception e) {
            return null;
        }
    }

    private Long resolveTargetId(ProceedingJoinPoint pjp, AdminLogRecord record) {
        int index = record.targetIdIndex();
        if (index < 0) {
            return null;
        }
        Object[] args = pjp.getArgs();
        if (index >= args.length || !(args[index] instanceof Long id)) {
            return null;
        }
        return id;
    }

    private String resolveDetail(ProceedingJoinPoint pjp, AdminLogRecord record) {
        if (record.detail().isBlank()) {
            return null;
        }
        try {
            StandardEvaluationContext ctx = new StandardEvaluationContext();
            Object[] args = pjp.getArgs();
            for (int i = 0; i < args.length; i++) {
                ctx.setVariable("p" + i, args[i]);
            }
            var expression = expressionCache.computeIfAbsent(record.detail(), parser::parseExpression);
            Object value = expression.getValue(ctx);
            return truncate(value == null ? null : String.valueOf(value), 1000);
        } catch (Exception e) {
            log.warn("解析操作日志详情表达式失败: {}", record.detail(), e);
            return null;
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
