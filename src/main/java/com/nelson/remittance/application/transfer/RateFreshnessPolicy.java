package com.nelson.remittance.application.transfer;

import java.time.Duration;

// 환율 스냅샷을 신선하다고 인정하는 최대 시차. 같은 타입의 Duration 빈과 구분되도록 주입용으로 감싼다.
// 허용 범위(1~3600초) 검증은 값을 읽는 설정 쪽이 기동 시점에 맡는다.
public record RateFreshnessPolicy(Duration maxAge) {}
