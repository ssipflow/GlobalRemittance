package com.nelson.remittance.adapter.web.error;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

// DB 연결 자체가 끊기거나 얻을 수 없어 잠시 뒤 재시도하면 성공할 수 있는 실패인지 원인 연쇄로 판별한다.
// 풀은 직전 500ms 안에 쓴 연결의 생존 확인을 건너뛰므로 DB가 연결을 끊은 직후 첫 요청이 이 실패를 만난다.
// 판별은 예외 클래스와 SQLState만 쓰고 메시지는 읽지 않는다(요청 값이 섞일 수 있다).
final class DatabaseUnavailability {
    private static final int MAX_CAUSES = 20;

    private DatabaseUnavailability() {}

    static boolean isUnavailable(Throwable error) {
        Throwable current = error;
        for (int i = 0; current != null && i < MAX_CAUSES; i++) {
            if (current instanceof SQLTransientConnectionException
                    || current instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (current instanceof SQLException sql && isConnectionState(sql.getSQLState())) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    // 08xxx는 표준 연결 예외 계열이고, 57P01~03은 PostgreSQL이 관리자 종료·위기 종료·DB 종료로 연결을 끊을 때의 상태다.
    // 잠금 대기 초과(55P03·HYT00)나 제약 위반(23xxx)은 연결 문제가 아니므로 포함하지 않는다.
    private static boolean isConnectionState(String sqlState) {
        if (sqlState == null) {
            return false;
        }
        // 08P01(프로토콜 위반)은 연결 끊김이 아닌 드라이버·서버 결함이라 재시도로 해결되지 않아 제외한다.
        return (sqlState.startsWith("08") && !"08P01".equals(sqlState))
                || "57P01".equals(sqlState)
                || "57P02".equals(sqlState)
                || "57P03".equals(sqlState);
    }
}
