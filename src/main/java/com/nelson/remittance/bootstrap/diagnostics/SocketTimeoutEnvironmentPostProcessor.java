package com.nelson.remittance.bootstrap.diagnostics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

// DB 소켓 타임아웃(초)은 postgres 프로파일의 JDBC 속성으로 들어가 잘못되면 첫 연결에서야 드러난다. DB 연결 전에 검증해 기동을 거부한다.
// 규칙: 1~4자리 ASCII 정수, 1~3600초, 그리고 잠금 대기 한도(ms)보다 커야 한다(작으면 정상 락 대기가 끊겨 503으로 오인된다).
public class SocketTimeoutEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String KEY = "remittance.db-socket-timeout-seconds";
    private static final String LOCK_KEY = "remittance.lock-timeout-ms";
    private static final int MAX_SECONDS = 3600;

    @Override
    // 설정 파일이 모두 로딩된 뒤 최종 값을 검사하며 속성이 없으면 검사하지 않는다.
    public void postProcessEnvironment(
            ConfigurableEnvironment environment, SpringApplication application) {
        String value = environment.getProperty(KEY);
        if (value == null) {
            return;
        }
        if (!value.matches("[1-9][0-9]{0,3}")) {
            throw new InvalidSocketTimeoutException(
                    value, InvalidSocketTimeoutException.Reason.FORMAT);
        }
        int seconds = Integer.parseInt(value);
        if (seconds > MAX_SECONDS) {
            throw new InvalidSocketTimeoutException(
                    value, InvalidSocketTimeoutException.Reason.RANGE);
        }
        Integer lockMs = validLockTimeoutMs(environment.getProperty(LOCK_KEY));
        // 잠금 대기 값이 없거나 형식·범위가 틀리면 비교하지 않는다. 처리기 실행 순서가 정해져 있지 않아도
        // 잘못된 잠금 대기 값은 LockTimeoutEnvironmentPostProcessor가 자기 안내로 거부하므로 여기서 중복·오도하지 않는다.
        if (lockMs != null && seconds * 1000L <= lockMs) {
            throw new InvalidSocketTimeoutException(
                    value, InvalidSocketTimeoutException.Reason.NOT_ABOVE_LOCK_TIMEOUT);
        }
    }

    // 잠금 대기 검증기와 같은 규칙(1~30000 ASCII 정수)을 만족할 때만 값을 돌려준다.
    private static Integer validLockTimeoutMs(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,4}")) {
            return null;
        }
        int ms = Integer.parseInt(value);
        return ms <= 30000 ? ms : null;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
