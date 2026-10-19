package com.wordtrail.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class JsonConfig {
    @Bean
    public JsonMapperBuilderCustomizer strictNumericInputs() {
        // Jackson 3 replaces the old ACCEPT_FLOAT_AS_INT / numeric enum feature flags.
        return builder -> builder
            .withCoercionConfig(LogicalType.Integer, config ->
                config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail))
            .withCoercionConfig(LogicalType.Enum, config ->
                config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail));
    }
}
