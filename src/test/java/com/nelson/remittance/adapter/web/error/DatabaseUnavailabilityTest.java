package com.nelson.remittance.adapter.web.error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;

// 연결 장애(재시도 가능한 503)와 그 밖의 DB 오류(잠금 대기·제약 위반·일반 결함)의 경계를 고정한다.
class DatabaseUnavailabilityTest {
    // 실제 예외처럼 JPA·Hibernate 래퍼 안쪽에 SQLException이 있는 원인 연쇄를 만든다.
    private static Throwable wrapped(SQLException sql) {
        return new RuntimeException("jpa", new IllegalStateException("hibernate", sql));
    }

    @Test
    // PostgreSQL이 연결을 끊을 때의 SQLState(57P01 관리자 종료 등)와 표준 08 계열은 연결 장애다.
    void connectionStatesAreUnavailable() {
        for (String state : new String[] {"57P01", "57P02", "57P03", "08001", "08003", "08006"}) {
            assertTrue(
                    DatabaseUnavailability.isUnavailable(wrapped(new SQLException("x", state))),
                    state);
        }
    }

    @Test
    // 풀이 연결을 얻지 못하거나 연결이 끊긴 경우의 JDBC 표준 예외는 SQLState가 없어도 연결 장애다.
    void jdbcConnectionExceptionsAreUnavailable() {
        assertTrue(
                DatabaseUnavailability.isUnavailable(
                        wrapped(new SQLTransientConnectionException("pool timeout"))));
        assertTrue(
                DatabaseUnavailability.isUnavailable(
                        wrapped(new SQLNonTransientConnectionException("closed"))));
    }

    @Test
    // 잠금 대기 초과·제약 위반·권한 등 연결과 무관한 DB 오류와 일반 결함은 503으로 바꾸지 않는다.
    void otherErrorsAreNotUnavailable() {
        for (String state :
                new String[] {
                    "55P03", "HYT00", "08P01", "23505", "42P01", "28P01", "3D000", null
                }) {
            assertFalse(
                    DatabaseUnavailability.isUnavailable(wrapped(new SQLException("x", state))),
                    String.valueOf(state));
        }
        assertFalse(DatabaseUnavailability.isUnavailable(new IllegalStateException("boom")));
    }

    @Test
    // 자기 자신을 원인으로 가진 예외와 매우 깊은 연쇄에서도 순회가 끝난다.
    void traversalTerminatesOnCyclesAndDepth() {
        Throwable deep = new SQLException("x", "57P01");
        for (int i = 0; i < 30; i++) {
            deep = new RuntimeException(deep);
        }
        // 깊이 상한(20)을 넘는 원인은 보지 않아 판별하지 않는다. 무한 순회만 없으면 된다.
        assertFalse(DatabaseUnavailability.isUnavailable(deep));
        RuntimeException selfCause =
                new RuntimeException("self") {
                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                };
        assertFalse(DatabaseUnavailability.isUnavailable(selfCause));
    }
}
