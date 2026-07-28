package vip.mate.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置
 * <p>
 * 1. 容错非标准 LLM 响应：启用 {@code READ_UNKNOWN_ENUM_VALUES_AS_NULL}
 * 2. Long→String：MyBatis Plus 生成的 19 位 Snowflake ID 超过 JS Number.MAX_SAFE_INTEGER (2^53-1)，
 *    序列化为字符串避免前端精度丢失。
 *
 * @author SnSclaw Team
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer enumTolerantCustomizer() {
        return builder -> builder.featuresToEnable(
                DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL
        );
    }

    /**
     * 全局 Long/long → String 序列化，防止前端 JS 精度丢失
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        };
    }
}
