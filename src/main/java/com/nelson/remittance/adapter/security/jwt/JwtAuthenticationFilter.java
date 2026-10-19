package com.nelson.remittance.adapter.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nelson.remittance.adapter.web.dto.Result;
import com.nelson.remittance.application.port.security.TokenProvider;
import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

// 송금 요청의 Bearer 토큰을 인증 주체로 바꾸며 실패 응답에는 내부 원인을 노출하지 않는다.
public final class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final TokenProvider tokens;
    private final ObjectMapper json;
    private final RequestMatcher publicPaths;

    // 공개 경로 매처는 SecurityConfiguration과 공유해 '토큰 없이 열린 경로'의 출처를 하나로 둔다.
    public JwtAuthenticationFilter(
            TokenProvider tokens, ObjectMapper json, RequestMatcher publicPaths) {
        this.tokens = tokens;
        this.json = json;
        this.publicPaths = publicPaths;
    }

    @Override
    // 공개 경로(가입·로그인·API 문서 등)는 토큰 검사 대상에서 제외하고 그 밖의 모든 경로는 검사한다.
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return publicPaths.matches(request);
    }

    @Override
    // 정상 토큰을 인증 주체로 등록하며 잘못된 헤더나 토큰은 체인을 진행하지 않고 JSON 401로 끝낸다.
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            try {
                // RFC 7235: 인증 스킴 이름은 대소문자를 구분하지 않는다.
                if (!header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
                    throw new BusinessException(ErrorCode.UNAUTHORIZED);
                }
                long id = tokens.verify(header.substring(7));
                SecurityContextHolder.getContext()
                        .setAuthentication(
                                new UsernamePasswordAuthenticationToken(id, null, List.of()));
            } catch (BusinessException error) {
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType("application/json");
                json.writeValue(response.getOutputStream(), new Result(401, "UNAUTHORIZED"));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
