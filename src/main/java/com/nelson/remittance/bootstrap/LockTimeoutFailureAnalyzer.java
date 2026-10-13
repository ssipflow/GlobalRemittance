package com.nelson.remittance.bootstrap;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// 잘못된 잠금 대기 설정을 DB 오류가 아닌 설정 오류로 안내한다. 값은 정리된 형태로만 출력한다.
public class LockTimeoutFailureAnalyzer
        extends AbstractFailureAnalyzer<InvalidLockTimeoutException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidLockTimeoutException cause) {
        return new FailureAnalysis(
                "REMITTANCE_LOCK_TIMEOUT_MS(remittance.lock-timeout-ms) 값 '"
                        + cause.safeValue()
                        + "'은(는) 사용할 수 없습니다. 1~30000 사이의 정수(ms)여야 합니다.",
                "REMITTANCE_LOCK_TIMEOUT_MS 환경변수 또는 --remittance.lock-timeout-ms 인자를 1~30000 사이의 정수로 고치거나 지워서 기본값 5000을 사용하세요.",
                cause);
    }
}
