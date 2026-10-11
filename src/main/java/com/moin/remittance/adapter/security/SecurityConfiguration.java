package com.moin.remittance.adapter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moin.remittance.adapter.web.ApiModels.Result;
import com.moin.remittance.application.Ports;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
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
