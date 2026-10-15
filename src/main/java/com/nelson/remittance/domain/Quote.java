package com.nelson.remittance.domain;

import static com.nelson.remittance.domain.BusinessException.Code.ALREADY_REQUESTED;
import static com.nelson.remittance.domain.BusinessException.Code.FORBIDDEN;
import static com.nelson.remittance.domain.BusinessException.Code.IDEMPOTENCY_KEY_REUSED;
import static com.nelson.remittance.domain.BusinessException.Code.QUOTE_EXPRIED;

import java.time.Duration;
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
    // 견적 발급 시점부터 접수할 수 있는 기간이다. 발급 후 환율은 이 기간 동안 고정된다.
    public static final Duration VALIDITY = Duration.ofMinutes(10);
    // 접수 성공 응답을 같은 키로 재현해 주는 기간이다. 이후에는 기존 중복 규칙(ALREADY_REQUESTED)을 따른다.
    public static final Duration IDEMPOTENT_REPLAY_WINDOW = Duration.ofHours(24);

    // 아직 접수되지 않은 새 견적이다. 식별자는 저장 시 부여된다.
    public static Quote issue(long userId, QuotePolicy.Calculation calculation, Instant now) {
        return new Quote(0, userId, calculation, now.plus(VALIDITY), null, null, now, now);
    }

    public boolean isOwnedBy(long userId) {
        return this.userId == userId;
    }

    // boolean을 돌려주지만 재사용된 키(다른 견적)면 BusinessException을 던진다.
    // previous는 같은 사용자·같은 멱등성 키로 이미 접수된 견적이다.
    // 다른 견적에 쓰인 키는 재사용 오류이고, 같은 견적이면 재현 기간 안에서만 true다.
    // true면 이미 접수된 건의 재현이므로 만료·한도를 다시 평가하지 않는다.
    // 기간이 지나면(경계 포함) false를 돌려 기존 중복 규칙에 맡긴다.
    public boolean replays(Quote previous, Instant now) {
        if (previous.id() != id) {
            throw new BusinessException(IDEMPOTENCY_KEY_REUSED);
        }
        return now.isBefore(previous.requestedTime().plus(IDEMPOTENT_REPLAY_WINDOW));
    }

    // 소유권, 중복, 만료 순으로 검증하고 기존 견적 계산을 유지한 새 접수 상태를 반환한다.
    // 키는 검증을 모두 통과한 접수에만 묶이므로 실패한 시도는 키를 소비하지 않는다.
    public Quote request(long actorId, Instant now, String idempotencyKey) {
        if (!isOwnedBy(actorId)) {
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
