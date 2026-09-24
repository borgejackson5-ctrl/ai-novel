package com.ainovel.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置：分页插件 + 字段自动填充
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页单页硬上限（兜底）：在拦截器层面拦截 {@code ?pageSize=100000} 这类请求。
     *
     * <p>取 200 而非对外接口的 100（{@code PageParam.MAX_PAGE_SIZE}）的原因：
     * 拦截器无法区分外部入参与内部批量查询，而内部确实存在按批遍历的场景
     * （搜索对账按批取 id 等）。兜底值必须不低于内部批量，否则会将内部查询静默改小，
     * 并导致「本批不满一页即取完」这类终止判断出错，该问题在数据量小时难以发现。
     *
     * <p>对外接口各自另有钳位（{@code PageParam.MAX_PAGE_SIZE} = 100），此处仅为最后一道防线。
     */
    private static final long MAX_LIMIT = 200L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 注意：setMaxLimit 返回 void，不能链式调用，必须单独一行配置后再注册
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(MAX_LIMIT);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * createTime / updateTime 自动填充
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
