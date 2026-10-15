package com.nelson.remittance.adapter.web.dto;

import com.nelson.remittance.domain.BusinessDay;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

// API 날짜 문자열 표현을 한 곳에서 정한다.
public final class ApiTime {
    private static final DateTimeFormatter KST_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(BusinessDay.KST);

    private ApiTime() {}

    // 저장은 UTC를 유지하고 API 날짜 문자열을 생성하는 경계에서 KST로 표시한다.
    public static String format(Instant instant) {
        return KST_TIME.format(instant);
    }
}
