package com.nelson.remittance.adapter.web.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// API 명세의 공통 정보와 Bearer JWT 인증 방식을 선언한다. 송금 API의 인증 요구는 컨트롤러에서 지정한다.
public class OpenApiConfiguration {
    // Swagger UI의 Authorize에서 로그인 응답의 token을 넣을 때 참조하는 스키마 이름이다.
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI remittanceOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("GlobalRemittance API")
                                .description(
                                        "회원가입·로그인, 환전 견적, 송금 접수, 접수 이력 조회 API."
                                                + " 모든 응답은 JSON이며 오류도 resultCode와 resultMsg 형식이다."
                                                + " 송금 API는 로그인으로 받은 JWT를 Authorize에 입력해 호출한다.")
                                .version("1.0.0"))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        BEARER_AUTH,
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description("로그인 응답의 token 값. 유효 시간은 30분이다.")));
    }
}
