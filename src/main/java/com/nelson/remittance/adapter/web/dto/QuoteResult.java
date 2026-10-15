package com.nelson.remittance.adapter.web.dto;

// 공통 성공 응답 안에 견적을 중첩한다.
public record QuoteResult(int resultCode, String resultMsg, QuoteView quote) {}
