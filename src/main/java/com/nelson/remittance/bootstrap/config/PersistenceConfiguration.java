package com.nelson.remittance.bootstrap.config;

import com.nelson.remittance.adapter.persistence.tx.RollbackSafeTransactionManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PersistenceConfiguration {
    @Bean
    // 롤백 실패가 원래 예외를 덮지 않는 매니저를 두면 Boot 기본 JpaTransactionManager 자동 구성이 물러난다.
    // Boot의 JpaBaseConfiguration이 물러나므로 spring.transaction.* 설정(기본 타임아웃 등)을 담은
    // TransactionManagerCustomizers를 Boot와 같은 방식으로 직접 적용해 설정이 조용히 무시되지 않게 한다.
    PlatformTransactionManager transactionManager(
            EntityManagerFactory factory,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        var manager = new RollbackSafeTransactionManager(factory);
        customizers.ifAvailable(c -> c.customize(manager));
        return manager;
    }
}
