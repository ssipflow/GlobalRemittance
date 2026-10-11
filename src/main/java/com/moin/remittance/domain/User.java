package com.moin.remittance.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record User(
        long id,
        String userId,
        String passwordHash,
        String name,
        IdentityType idType,
        String encryptedIdentity,
        Instant createdAt,
        Instant updatedAt) {
    public enum IdentityType {
        REG_NO("1000.00"),
        BUSINESS_NO("5000.00");
        private final BigDecimal dailyLimit;

        IdentityType(String limit) {
            dailyLimit = new BigDecimal(limit);
        }

        public BigDecimal dailyLimit() {
            return dailyLimit;
        }
    }

    // 인증·식별 정보가 디버그 로그에서 노출되지 않도록 자동 record 표현을 사용하지 않는다.
    @Override
    public String toString() {
        return "User[id=" + id + "]";
    }
}
