package com.nelson.remittance.adapter.web;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfiguration {
    @Bean
    // 문자열 식별자와 숫자 금액의 자동 변환을 차단하여 JSON 타입 계약을 보존한다.
    Jackson2ObjectMapperBuilderCustomizer strictJson() {
        return builder ->
                builder.postConfigurer(
                        mapper -> {
                            // JSON 문자열 식별자와 숫자 금액을 서로 강제 변환하지 않아 API 타입 계약을 지킨다.
                            var textual = mapper.coercionConfigFor(LogicalType.Textual);
                            for (var shape :
                                    new CoercionInputShape[] {
                                        CoercionInputShape.Integer,
                                        CoercionInputShape.Float,
                                        CoercionInputShape.Boolean
                                    }) {
                                textual.setCoercion(shape, CoercionAction.Fail);
                            }
                            mapper.coercionConfigFor(LogicalType.Float)
                                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
                        });
    }
}
