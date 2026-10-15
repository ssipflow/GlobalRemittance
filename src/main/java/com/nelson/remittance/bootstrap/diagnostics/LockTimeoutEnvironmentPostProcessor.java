package com.nelson.remittance.bootstrap.diagnostics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

// 잠금 대기 설정은 H2 URL에 그대로 들어가므로 잘못되면 DB 접속 실패처럼 보인다. DB 연결 전에 값을 검증해 기동을 거부한다.
public class LockTimeoutEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String KEY = "remittance.lock-timeout-ms";
    private static final int MAX_MS = 30000;

    @Override
    // 설정 파일이 모두 로딩된 뒤 최종 값을 검사하며 속성이 없으면 검사하지 않는다.
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        String value = environment.getProperty(KEY);
        if (value != null && !isValid(value)) {
            throw new InvalidLockTimeoutException(value);
        }
    }

    // trim·부호·소수·단위·선행 0을 허용하지 않는 1~5자리 ASCII 숫자만 받는다. 상한은 대기 요청이 Tomcat 스레드와 DB 연결(풀 10개)을 오래 붙잡지
    // 않게 하려는 값이다.
    private static boolean isValid(String value) {
        if (!value.matches("[1-9][0-9]{0,4}")) {
            return false;
        }
        int ms = Integer.parseInt(value);
        return ms >= 1 && ms <= MAX_MS;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
