package com.moin.globalRemittance.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moin.globalRemittance.dto.IUserDto;
import com.moin.globalRemittance.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final List<String> EXCLUDE_URLS = List.of(
            "/public",
            "/h2-console",
            "/user/signup",
            "/user/login"
    );

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        final String requestUri = request.getRequestURI();
        if (EXCLUDE_URLS.stream().anyMatch(requestUri::startsWith)) {
            filterChain.doFilter(request, response);
            return ;
        }

        final String authorizationHeader = request.getHeader("Authorization");

        String jwt = null;
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            jwt = authorizationHeader.substring(7);
        }

        try {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                IUserDto user = authService.authorization(jwt);

                if (user != null) {
                    UsernamePasswordAuthenticationToken authenticationToken
                            = new UsernamePasswordAuthenticationToken(user, null, null);
                    authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                }
            }

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            Map<String, Object> errResponse = new HashMap<>();
            errResponse.put("resultCode" ,HttpServletResponse.SC_UNAUTHORIZED);
            errResponse.put("resultMsg" ,"Unauthorized: " + e.getMessage());

            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(errResponse));
            response.getWriter().flush();
            response.getWriter().close();
        }
    }
}
