package com.nelson.remittance.adapter.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
// 모든 요청에 요청 ID를 부여해 응답 헤더와 로그(MDC)로 연결한다. 보안 필터보다 먼저 실행되어 401·404도 포함한다.
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    // 안전한 클라이언트 값만 채택하고 아니면 새 UUID를 만든다. 요청이 끝나면 스레드 재사용에 대비해 MDC를 반드시 제거한다.
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        // 로그 주입을 막기 위해 허용 문자만으로 이루어진 값만 그대로 쓴다.
        String id =
                supplied != null && SAFE.matcher(supplied).matches()
                        ? supplied
                        : UUID.randomUUID().toString();
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
