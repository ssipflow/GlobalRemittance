package com.nelson.remittance.application.port.repository;

import java.time.Instant;

// 접수 목록 위치다. 마지막으로 읽은 항목의 (접수 시각, ID)이며 인코딩 방식은 모른다.
public record QuotePosition(Instant requestedTime, long id) {}
