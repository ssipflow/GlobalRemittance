package com.nelson.remittance.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

// 일일 한도와 이력 집계의 기준일 규칙이다. 기준일은 KST 날짜의 [00:00, 익일 00:00) 구간이며
// API 표시 시간대도 여기가 단일 출처다. Asia/Seoul은 현재 서머타임이 없어 오늘 기준 하루는 24시간이지만,
// tzdb 이력상 1987~88년에는 서머타임이 있었으므로 과거 날짜의 길이까지 보장하지는 않는다.
public record BusinessDay(Instant start, Instant end) {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static BusinessDay of(Instant now) {
        LocalDate date = now.atZone(KST).toLocalDate();
        return new BusinessDay(
                date.atStartOfDay(KST).toInstant(), date.plusDays(1).atStartOfDay(KST).toInstant());
    }
}
