package com.nelson.remittance.adapter.persistence.repository;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;

// 저장소가 공통으로 쓰는 DB 예외 판별을 모아, 원인 연쇄 순회 규칙을 한 곳에서 유지한다.
final class PersistenceErrors {
    private PersistenceErrors() {}

    // 락 대기 초과를 JPA 예외 또는 DB 상태 코드(H2·표준 HYT00, PostgreSQL 55P03)로 식별한다.
    static boolean isLockTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = next(cause)) {
            if (cause instanceof LockTimeoutException
                    || cause instanceof PessimisticLockException) {
                return true;
            }
            if (cause instanceof SQLException sql
                    && ("HYT00".equals(sql.getSQLState()) || "55P03".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    // 원인 연쇄에 유일 제약 위반(SQLState 23505)이 있는지 판별한다. 어떤 제약인지는 호출자가 문맥으로 해석한다.
    static boolean isUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = next(cause)) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    // 자기 자신을 원인으로 가진 예외로 순회가 끝나지 않는 일을 막는다.
    private static Throwable next(Throwable error) {
        Throwable cause = error.getCause();
        return cause == error ? null : cause;
    }
}
