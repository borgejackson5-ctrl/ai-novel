package com.ainovel.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局序列化配置
 *
 * <p>背景：数据库主键使用雪花算法生成（19 位 Long），超出 JS Number 的安全整数范围
 * （2^53-1），若以 JSON 数字返回，前端 JSON.parse 会丢失精度，导致回查接口 404。
 *
 * <p>方案：全局将 Long 及 long 类型序列化为字符串（业界标准做法）。
 * Integer 等小整数字段不受影响，前端状态判断（=== 1 等）保持安全。
 *
 * <p>作用域说明：仅影响 Spring MVC 的 HTTP JSON 响应；
 * Redis（GenericJackson2JsonRedisSerializer）、RabbitMQ（Jackson2JsonMessageConverter）
 * 及 AiClient 均使用独立 new 的 ObjectMapper，不受本配置影响。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
