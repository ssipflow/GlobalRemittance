package com.nelson.remittance.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

// 환율과 그 환율이 유효했던 기준 시각을 함께 묶는다. 값만으로는 오래된 환율을 구분할 수 없기 때문이다.
public record RateSnapshot(BigDecimal toKrw, Instant asOf) {
    public RateSnapshot {
        Objects.requireNonNull(toKrw);
        Objects.requireNonNull(asOf);
    }

    // 기준 시각과 현재의 거리가 maxAge 이하면 신선하다(경계 포함).
    // 절댓값을 쓰는 이유: 미래 기준 시각은 공급처나 서버의 시계 어긋남 신호이므로 과거와 같은 허용 범위로 제한한다.
    public boolean isFresh(Instant now, Duration maxAge) {
        return Duration.between(asOf, now).abs().compareTo(maxAge) <= 0;
    }
}
