package com.nelson.remittance.domain;

import static com.nelson.remittance.domain.BusinessException.Code.ALREADY_REQUESTED;
import static com.nelson.remittance.domain.BusinessException.Code.FORBIDDEN;
import static com.nelson.remittance.domain.BusinessException.Code.QUOTE_EXPRIED;

import java.time.Instant;

// 견적에 확정한 금액과 시각을 보관하며 소유권·중복·만료 조건에 따라 접수 상태를 만든다.
// idempotencyKey는 접수에 성공한 요청의 멱등성 키이며, 키 없이 접수했거나 미접수면 null이다.
public record Quote(
        long id,
        long userId,
        QuotePolicy.Calculation calculation,
        Instant expireTime,
        Instant requestedTime,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt) {
    // 소유권, 중복, 만료 순으로 검증하고 기존 견적 계산을 유지한 새 접수 상태를 반환한다.
    // 키는 검증을 모두 통과한 접수에만 묶이므로 실패한 시도는 키를 소비하지 않는다.
    public Quote request(long actorId, Instant now, String idempotencyKey) {
        if (userId != actorId) {
            throw new BusinessException(FORBIDDEN);
        }
        if (requestedTime != null) {
            throw new BusinessException(ALREADY_REQUESTED);
        }
        // 만료 시각과 같은 순간도 만료다. 시간 경계는 서버의 주입된 Clock으로 판단한다.
        if (!now.isBefore(expireTime)) {
            throw new BusinessException(QUOTE_EXPRIED);
        }
        return new Quote(id, userId, calculation, expireTime, now, idempotencyKey, createdAt, now);
    }
}
