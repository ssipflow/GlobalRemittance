package com.nelson.remittance.domain;

// 업무 실패를 고정된 코드로 전달하며 HTTP 상태 결정은 웹 어댑터에 맡긴다.
public final class BusinessException extends RuntimeException {
    public enum Code {
        INVALID_REQUEST,
        NEGATIVE_NUMBER,
        UNAUTHORIZED,
        FORBIDDEN,
        DUPLICATE_USER,
        QUOTE_NOT_FOUND,
        ALREADY_REQUESTED,
        QUOTE_EXPRIED,
        LIMIT_EXCESS,
        LOCK_TIMEOUT,
        UNKNOWN_ERROR
    }

    private final Code code;

    public BusinessException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
