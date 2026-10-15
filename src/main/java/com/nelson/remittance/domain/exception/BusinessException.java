package com.nelson.remittance.domain.exception;

// 업무 실패를 고정된 코드로 전달하며 HTTP 상태 결정은 웹 어댑터에 맡긴다.
public final class BusinessException extends RuntimeException {
    private final ErrorCode code;

    public BusinessException(ErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
