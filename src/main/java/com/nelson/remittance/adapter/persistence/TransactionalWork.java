package com.nelson.remittance.adapter.persistence;

import com.nelson.remittance.application.Ports;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TransactionalWork implements Ports.Work {
    private final TransactionTemplate transaction;

    public TransactionalWork(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(manager);
    }

    @Override
    public <T> T required(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }
}
