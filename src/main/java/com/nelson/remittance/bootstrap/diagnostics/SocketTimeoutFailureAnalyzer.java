package com.nelson.remittance.bootstrap.diagnostics;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// 잘못된 DB 소켓 타임아웃 설정을 DB 오류가 아닌 설정 오류로 안내한다. 어느 제약을 어겼는지 밝히고 값은 정리된 형태로만 출력한다.
public class SocketTimeoutFailureAnalyzer
        extends AbstractFailureAnalyzer<InvalidSocketTimeoutException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidSocketTimeoutException cause) {
        String why =
                switch (cause.reason()) {
                    case FORMAT -> "부호·공백·소수·단위·선행 0이 없는 1~4자리 숫자여야 합니다.";
                    case RANGE -> "1~3600 사이의 정수(초)여야 합니다.";
                    case NOT_ABOVE_LOCK_TIMEOUT ->
                            "잠금 대기 한도(remittance.lock-timeout-ms)보다 커야 합니다. 작거나 같으면 정상적인 잠금 대기가 DB 장애(503)로 오인됩니다.";
                };
        return new FailureAnalysis(
                "REMITTANCE_DB_SOCKET_TIMEOUT_SECONDS(remittance.db-socket-timeout-seconds) 값 '"
                        + cause.safeValue()
                        + "'은(는) 사용할 수 없습니다. "
                        + why,
                "REMITTANCE_DB_SOCKET_TIMEOUT_SECONDS 환경변수 또는 --remittance.db-socket-timeout-seconds 인자를 규칙에 맞게 고치거나 지워서 기본값 60을 사용하세요.",
                cause);
    }
}
