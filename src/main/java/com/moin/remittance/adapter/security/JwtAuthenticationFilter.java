package com.moin.remittance.adapter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moin.remittance.adapter.web.ApiModels.Result;
import com.moin.remittance.application.Ports;
import com.moin.remittance.domain.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public final class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final Ports.Tokens tokens;
    private final ObjectMapper json;

    public JwtAuthenticationFilter(Ports.Tokens tokens, ObjectMapper json) {
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        return !(path.equals("/transfer") || path.startsWith("/transfer/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            try {
                if (!header.startsWith("Bearer ") || header.length() <= 7) {
                    throw new BusinessException(BusinessException.Code.UNAUTHORIZED);
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
