package com.nelson.remittance.application.port.security;

// 저장할 식별 번호를 암호화하며 개인 정보 복호화 기능은 사용 사례에 노출하지 않는다.
public interface IdentityCipher {
    String encrypt(String value);
}
