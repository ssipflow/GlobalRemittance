package com.nelson.remittance.adapter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.adapter.web.ApiModels.Result;
import com.nelson.remittance.application.Ports;
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
    SecurityFilterChain security(HttpSecurity http, Ports.Tokens tokens, ObjectMapper json)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers("/transfer", "/transfer/**")
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
