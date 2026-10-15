package com.nelson.remittance.application.port.security;

import com.nelson.remittance.domain.user.User;

// 토큰을 발급하고 정상 서명·만료 조건을 통과한 사용자 식별자만 돌려준다.
public interface TokenProvider {
    String issue(User user);

    long verify(String token);
}
