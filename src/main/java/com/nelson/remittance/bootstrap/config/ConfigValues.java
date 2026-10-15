package com.nelson.remittance.bootstrap.config;

// 문자열로 받은 설정값을 기동 시점에 엄격하게 해석한다. 잘못된 값은 설정 키와 함께 거부한다.
final class ConfigValues {
    private ConfigValues() {}

    // 부호·공백·소수를 허용하지 않는 ASCII 정수만 받는다. 오류 메시지에는 설정 키와 허용 범위만 담는다.
    static int integer(String key, String value, int min, int max) {
        if (value.matches("[0-9]{1,9}")) {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        }
        throw new IllegalArgumentException(key + "는 " + min + " 이상 " + max + " 이하의 정수여야 합니다.");
    }

    static boolean bool(String key, String value) {
        if ("true".equals(value) || "false".equals(value)) {
            return Boolean.parseBoolean(value);
        }
        throw new IllegalArgumentException(key + "는 true 또는 false여야 합니다.");
    }
}
