package com.nelson.remittance.adapter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.adapter.web.ApiModels.Result;
import com.nelson.remittance.application.port.TokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
// 세션 없이 송금 API의 인증을 강제하고 인증·인가 오류도 JSON으로 응답한다.
public class SecurityConfiguration {
    @Bean
    // Bearer 인증 경로와 오류 처리를 설정한다. 별도 세션 로그인과 CSRF 쿠키 인증은 사용하지 않는다.
    SecurityFilterChain security(
            HttpSecurity http,
            TokenProvider tokens,
            ObjectMapper json,
            @Value("${spring.h2.console.enabled:false}") boolean h2ConsoleEnabled)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                // H2 콘솔은 프레임으로 화면을 구성해 기본 X-Frame-Options: DENY에서는 로그인 뒤 화면이 막힌다.
                // 콘솔이 켜진 경우(local 프로파일)에만 같은 출처 프레임을 허용하고, 꺼져 있으면 기존 DENY를 유지한다.
                // 위험 범위는 같은 출처 프레임뿐이며 다른 출처 페이지가 이 앱을 프레임에 넣을 수는 없다.
                .headers(
                        headers -> {
                            if (h2ConsoleEnabled) {
                                headers.frameOptions(frame -> frame.sameOrigin());
                            }
                        })
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                // API 명세와 Swagger UI는 토큰을 얻기 전에도 열람해야 하므로 인증 없이 연다.
                                // 노출 여부는 REMITTANCE_API_DOCS_ENABLED로 제어한다.
                                auth.requestMatchers(
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs/**")
                                        .permitAll()
                                        .requestMatchers("/transfer", "/transfer/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, error) -> {
                                                    response.setStatus(401);
                                                    response.setContentType("application/json");
                                                    json.writeValue(
                                                            response.getOutputStream(),
                                                            new Result(401, "UNAUTHORIZED"));
                                                })
                                        .accessDeniedHandler(
                                                (request, response, error) -> {
                                                    response.setStatus(403);
                                                    response.setContentType("application/json");
                                                    json.writeValue(
                                                            response.getOutputStream(),
                                                            new Result(403, "FORBIDDEN"));
                                                }))
                .addFilterBefore(
                        new JwtAuthenticationFilter(tokens, json),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
