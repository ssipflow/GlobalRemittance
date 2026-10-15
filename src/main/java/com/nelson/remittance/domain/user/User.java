package com.nelson.remittance.domain.user;

import static com.nelson.remittance.domain.exception.ErrorCode.LIMIT_EXCESS;

import com.nelson.remittance.domain.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Instant;

// 가입 사용자와 일일 송금 한도 정책을 표현하는 프레임워크 독립 모델이다.
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

    // 한도와 동일한 금액은 허용하고 초과만 막는다. 오늘 합계와 새 금액은 모두 견적 시 확정된 USD 값이다.
    public void checkDailyLimit(BigDecimal requestedToday, BigDecimal newAmount) {
        if (requestedToday.add(newAmount).compareTo(idType.dailyLimit()) > 0) {
            throw new BusinessException(LIMIT_EXCESS);
        }
    }

    // 인증·식별 정보가 디버그 로그에서 노출되지 않도록 자동 record 표현을 사용하지 않는다.
    @Override
    public String toString() {
        return "User[id=" + id + "]";
    }
}
