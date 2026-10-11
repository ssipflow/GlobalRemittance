package com.nelson.remittance.adapter.persistence;

import com.nelson.remittance.application.Ports;
import com.nelson.remittance.domain.BusinessException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
// 내부 트랜잭션 포트를 Spring 트랜잭션으로 구현하여 예외 시 전체 업무 변경을 되돌린다.
public class TransactionalWork implements Ports.Work {
    private final TransactionTemplate transaction;

    public TransactionalWork(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
    }

    @Override
    // 동일 트랜잭션 안에서 사용자 잠금·조회·접수를 수행하고 예외는 커밋하지 않는다.
    public <T> T required(Supplier<T> action) {
        var failure = new AtomicReference<RuntimeException>();
        try {
            return transaction.execute(
                    status -> {
                        try {
                            return action.get();
                        } catch (RuntimeException error) {
                            failure.set(error);
                            throw error;
                        }
                    });
        } catch (RuntimeException rollbackError) {
            // 락 타임아웃 등으로 커넥션이 이미 끊기면 롤백도 실패한다. 호출자에게는 원래 업무 예외를 전달한다.
            if (failure.get() instanceof BusinessException original) {
                throw original;
            }
            throw rollbackError;
        }
    }
}
