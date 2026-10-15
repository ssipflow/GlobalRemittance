package com.nelson.remittance.domain.exception;

// 업무 실패를 구분하는 고정된 오류 코드.
public enum ErrorCode {
    INVALID_REQUEST,
    NEGATIVE_NUMBER,
    UNAUTHORIZED,
    FORBIDDEN,
    DUPLICATE_USER,
    QUOTE_NOT_FOUND,
    ALREADY_REQUESTED,
    QUOTE_EXPRIED,
    LIMIT_EXCESS,
    IDEMPOTENCY_KEY_REUSED,
    LOCK_TIMEOUT,
    RATE_UNAVAILABLE,
    RATE_STALE,
    UNKNOWN_ERROR
}
