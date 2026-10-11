package com.moin.remittance.domain;

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
