package com.nelson.remittance.application.port;

import com.nelson.remittance.domain.RateSnapshot;

// 대상 통화 한 단위당 KRW 환율을 기준 시각과 함께 제공하며 내부 계층은 제공처 구현을 알지 못한다.
// 공급 실패는 어댑터가 BusinessException(RATE_UNAVAILABLE)로 알리고, 미지원 통화는 INVALID_REQUEST다.
// 신선도 판단은 정책이므로 어댑터가 아니라 사용 사례가 맡는다.
public interface RateProvider {
    RateSnapshot rate(String currency);
}
