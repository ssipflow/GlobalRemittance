package com.nelson.remittance.domain;

import static com.nelson.remittance.domain.BusinessException.Code.ALREADY_REQUESTED;
import static com.nelson.remittance.domain.BusinessException.Code.FORBIDDEN;
import static com.nelson.remittance.domain.BusinessException.Code.QUOTE_EXPRIED;

import java.time.Instant;

public record Quote(
        long id,
        long userId,
        QuotePolicy.Calculation calculation,
        Instant expireTime,
        Instant requestedTime,
        Instant createdAt,
        Instant updatedAt) {
    public Quote request(long actorId, Instant now) {
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
        return new Quote(id, userId, calculation, expireTime, now, createdAt, now);
    }
}
