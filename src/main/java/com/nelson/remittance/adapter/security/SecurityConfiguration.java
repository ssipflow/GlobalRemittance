package com.nelson.remittance.adapter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.adapter.security.jwt.JwtAuthenticationFilter;
import com.nelson.remittance.adapter.web.dto.Result;
import com.nelson.remittance.application.port.security.TokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

@Configuration
// 세션 없이 송금 API의 인증을 강제하고 인증·인가 오류도 JSON으로 응답한다.
public class SecurityConfiguration {
    // 토큰 없이 열어 두는 경로의 단일 출처다. JWT 필터도 같은 매처로 공개 경로의 검사를 건너뛴다.
    // API 명세·Swagger UI는 토큰을 얻기 전에도 열람해야 하며 노출 여부는 REMITTANCE_API_DOCS_ENABLED로 제어한다.
    // H2 콘솔 경로는 콘솔이 꺼져 있으면 서블릿이 없어 404가 나므로 항상 열어 두어도 노출이 늘지 않는다.
    // /error는 서블릿 오류 디스패치가 JSON 오류 본문을 만들 수 있게 열어 둔다.
    private static final RequestMatcher PUBLIC_PATHS =
            new OrRequestMatcher(
                    Stream.of(
                                    "/user/signup",
                                    "/user/login",
                                    "/swagger-ui.html",
                                    "/swagger-ui/**",
                                    "/v3/api-docs/**",
                                    "/h2-console",
                                    "/h2-console/**",
                                    "/error")
                            .<RequestMatcher>map(AntPathRequestMatcher::new)
                            .toList());

    // 인증된 사용자는 통과, 익명은 컨트롤러가 매핑된 경로에서만 거부한다.
    // 매핑 조회가 예외(예: 메서드 불일치 405)를 던지면 매핑된 경로로 보고 거부한다.
    private static AuthorizationManager<RequestAuthorizationContext> authenticatedOrUnmapped(
            ObjectProvider<RequestMappingHandlerMapping> mappings) {
        return (authentication, context) -> {
            Authentication current = authentication.get();
            if (current != null
                    && current.isAuthenticated()
                    && !(current instanceof AnonymousAuthenticationToken)) {
                return new AuthorizationDecision(true);
            }
            return new AuthorizationDecision(!hasHandler(mappings, context.getRequest()));
        };
    }

    // DispatcherServlet 이전 단계라 파싱된 경로가 없으므로 임시로 캐시한 뒤 조회하고 되돌린다.
    private static boolean hasHandler(
            ObjectProvider<RequestMappingHandlerMapping> mappings, HttpServletRequest request) {
        boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
        try {
            // 매핑 빈이 여러 개여서 단일 조회가 실패하는 경우도 500이 아니라 매핑된 경로로 보고 거부한다.
            // 이 방식은 RequestMappingHandlerMapping이 모르는 핸들러(정적 리소스, actuator)를 미매핑으로 보므로
            // 그런 의존성을 추가할 때는 해당 경로를 공개/보호 목록에 명시해야 한다.
            RequestMappingHandlerMapping mapping = mappings.getIfAvailable();
            if (mapping == null) {
                return true;
            }
            if (parsedHere) {
                ServletRequestPathUtils.parseAndCache(request);
            }
            return mapping.getHandler(request) != null;
        } catch (Exception error) {
            return true;
        } finally {
            if (parsedHere) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }

    @Bean
    // Bearer 인증 경로와 오류 처리를 설정한다. 별도 세션 로그인과 CSRF 쿠키 인증은 사용하지 않는다.
    SecurityFilterChain security(
            HttpSecurity http,
            TokenProvider tokens,
            ObjectMapper json,
            ObjectProvider<RequestMappingHandlerMapping> mappings,
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
                // 공개 경로만 명시적으로 열고, 나머지는 인증을 기본으로 요구한다(기본 거부).
                // 단 컨트롤러가 없는 경로까지 401로 바꾸면 '없는 경로는 404 JSON' 계약이 깨지므로,
                // 인증 없는 요청은 핸들러가 없는 경로에서만 통과시켜 404가 나게 한다.
                // 새 컨트롤러가 추가되면 별도 설정 없이도 자동으로 인증 대상이 된다.
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(PUBLIC_PATHS)
                                        .permitAll()
                                        // 송금 API 영역은 컨트롤러 유무와 관계없이 항상 인증을 요구한다(가상 하위 경로도 401).
                                        .requestMatchers("/transfer", "/transfer/**")
                                        .authenticated()
                                        .anyRequest()
                                        .access(authenticatedOrUnmapped(mappings)))
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
                        new JwtAuthenticationFilter(tokens, json, PUBLIC_PATHS),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
