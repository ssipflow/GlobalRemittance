package com.nelson.remittance.bootstrap.diagnostics;

// 잠금 대기 설정이 허용 범위를 벗어났음을 알린다. 값은 출력 안전하게 정리해 보관하고 메시지에는 넣지 않는다.
public class InvalidLockTimeoutException extends RuntimeException {
    private static final int MAX_LENGTH = 20;

    private final String safeValue;

    public InvalidLockTimeoutException(String rawValue) {
        super("remittance.lock-timeout-ms must be an integer between 1 and 30000");
        this.safeValue = sanitize(rawValue);
    }

    public String safeValue() {
        return safeValue;
    }

    // 설정 값은 외부 입력이므로 개행·제어 문자가 로그를 위조하지 못하게 치환하고 길이를 제한한다.
    private static String sanitize(String raw) {
        String safe = raw == null ? "" : raw.replaceAll("[^\\x21-\\x7E]", "_");
        return safe.length() > MAX_LENGTH ? safe.substring(0, MAX_LENGTH) : safe;
    }
}
