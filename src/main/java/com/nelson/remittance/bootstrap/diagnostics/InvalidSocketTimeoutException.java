package com.nelson.remittance.bootstrap.diagnostics;

// DB 소켓 타임아웃 설정이 규칙을 어겼음을 알린다. 값은 출력 안전하게 정리해 보관하고 메시지에는 넣지 않는다.
public class InvalidSocketTimeoutException extends RuntimeException {
    private static final int MAX_LENGTH = 20;

    // 어느 제약을 어겼는지 구분한다.
    public enum Reason {
        FORMAT,
        RANGE,
        NOT_ABOVE_LOCK_TIMEOUT
    }

    private final String safeValue;
    private final Reason reason;

    public InvalidSocketTimeoutException(String rawValue, Reason reason) {
        super("remittance.db-socket-timeout-seconds is invalid: " + reason);
        this.safeValue = sanitize(rawValue);
        this.reason = reason;
    }

    public String safeValue() {
        return safeValue;
    }

    public Reason reason() {
        return reason;
    }

    // 설정 값은 외부 입력이므로 개행·제어 문자가 로그를 위조하지 못하게 치환하고 길이를 제한한다.
    private static String sanitize(String raw) {
        String safe = raw == null ? "" : raw.replaceAll("[^\\x21-\\x7E]", "_");
        return safe.length() > MAX_LENGTH ? safe.substring(0, MAX_LENGTH) : safe;
    }
}
