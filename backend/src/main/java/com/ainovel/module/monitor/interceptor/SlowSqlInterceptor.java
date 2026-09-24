package com.ainovel.module.monitor.interceptor;

import com.ainovel.module.monitor.metrics.MetricsCollector;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.stereotype.Component;

/**
 * 慢 SQL 埋点（MyBatis 插件）
 *
 * <p>仅在**超过阈值时**获取 SQL 文本：获取文本需重新构建 BoundSql，存在成本；
 * 若每条快 SQL 都承担该成本，观测自身会拖慢系统。正常路径只执行两次
 * {@code System.currentTimeMillis()}，仅慢 SQL 有额外开销。
 *
 * <p>与接口埋点一致，此处所有异常均被吞掉：观测代码不应改变业务行为。
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
@RequiredArgsConstructor
public class SlowSqlInterceptor implements Interceptor {

    /** SQL 文本截断长度：慢日志供人工排查，无需完整语句 */
    private static final int SQL_MAX_LEN = 500;

    private final MetricsCollector metricsCollector;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            record(invocation, System.currentTimeMillis() - start);
        }
    }

    private void record(Invocation invocation, long costMs) {
        if (costMs < metricsCollector.getSlowSqlThresholdMs()) {
            return;
        }
        try {
            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;
            String sql = sqlOf(ms, parameter);
            metricsCollector.recordSql(ms.getId(), sql, costMs);
        } catch (Exception ignored) {
            // 获取 SQL 失败不影响业务流程，最多少记录一条慢 SQL
        }
    }

    private static String sqlOf(MappedStatement ms, Object parameter) {
        BoundSql boundSql = ms.getBoundSql(parameter);
        String sql = boundSql.getSql();
        if (sql == null) {
            return null;
        }
        // 合并换行与多余空白，便于日志单行展示
        String normalized = sql.replaceAll("\\s+", " ").trim();
        return normalized.length() > SQL_MAX_LEN
                ? normalized.substring(0, SQL_MAX_LEN) + "..."
                : normalized;
    }
}
