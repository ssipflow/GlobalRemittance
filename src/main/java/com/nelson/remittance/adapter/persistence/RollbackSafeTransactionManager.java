package com.nelson.remittance.adapter.persistence;

import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

// 롤백 자체가 실패해도 호출자가 원래 예외를 받게 하는 JPA 트랜잭션 매니저다.
// 락 타임아웃 등으로 커넥션이 이미 끊기면 롤백도 예외를 던지고, 기본 구현은 그 예외로 원래 업무 예외를 덮는다.
public class RollbackSafeTransactionManager extends JpaTransactionManager {
    private static final Logger LOG = LoggerFactory.getLogger(RollbackSafeTransactionManager.class);

    public RollbackSafeTransactionManager(EntityManagerFactory factory) {
        super(factory);
    }

    @Override
    // 롤백은 이미 실패 경로이므로 예외를 삼켜 원래 예외가 전파되게 한다. 리소스 정리는 상위 흐름의 finally가 계속 수행한다.
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            super.doRollback(status);
        } catch (RuntimeException error) {
            // 메시지에는 SQL·사용자 값이 섞일 수 있어 예외 클래스와 SQLState만 남긴다.
            LOG.warn(
                    "트랜잭션 롤백 실패를 무시하고 원래 예외를 전달한다. exception={} sqlState={}",
                    error.getClass().getName(),
                    sqlState(error));
        }
    }

    private static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
