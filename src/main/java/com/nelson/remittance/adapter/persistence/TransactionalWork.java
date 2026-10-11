package com.nelson.remittance.adapter.persistence;

import com.nelson.remittance.application.Ports;
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
        return transaction.execute(status -> action.get());
    }
}
