package com.nelson.remittance.application.port;

// 원문 비밀번호를 단방향 해시하고 저장된 해시와 비교한다.
public interface PasswordHasher {
    String hash(String raw);

    boolean matches(String raw, String encoded);
}
