package com.moin.globalRemittance.repository.custom;

import com.moin.globalRemittance.entities.QRemittance;
import com.moin.globalRemittance.entities.Remittance;
import com.moin.globalRemittance.repository.model.RemittanceDto;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class TxTestRepository {

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    private final QRemittance remittance = QRemittance.remittance;

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Integer txRequiresNewTest1() {
        Remittance target = RemittanceDto.builder()
                .userId(1)
                .sourceAmount(BigDecimal.valueOf(1000001))
                .targetAmount(BigDecimal.valueOf(724.101053435115))
                .targetCurrency("USD")
                .exchangeRate(BigDecimal.valueOf(1375.50))
                .fixedFee(BigDecimal.valueOf(3000))
                .ratedFee(BigDecimal.valueOf(1000.001))
                .feeRatio(BigDecimal.valueOf(0.001))
                .usdAmount(BigDecimal.valueOf(724.101053435115))
                .usdExchangeRate(BigDecimal.valueOf(1375.50))
                .expireTime(Instant.now().plusMillis(10 * 60 * 1000))
                .updatedAt(Instant.now())
                .createdAt(Instant.now())
                .build()
                .toEntity();

        entityManager.persist(target);
        return target.getId();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Long txRequiresNewTest2(Integer id) {
        if (true) {
            throw new RuntimeException("GENERATED_ERR");
        }
        return queryFactory.update(remittance)
                .set(remittance.requestedTime, Instant.now())
                .set(remittance.updatedAt, Instant.now())
                .where(remittance.id.eq(id)
                        .and(remittance.requestedTime.isNull()))
                .execute();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRED)
    public Integer txRequiredTest1() {
        Remittance target = RemittanceDto.builder()
                .userId(1)
                .sourceAmount(BigDecimal.valueOf(1000001))
                .targetAmount(BigDecimal.valueOf(724.101053435115))
                .targetCurrency("USD")
                .exchangeRate(BigDecimal.valueOf(1375.50))
                .fixedFee(BigDecimal.valueOf(3000))
                .ratedFee(BigDecimal.valueOf(1000.001))
                .feeRatio(BigDecimal.valueOf(0.001))
                .usdAmount(BigDecimal.valueOf(724.101053435115))
                .usdExchangeRate(BigDecimal.valueOf(1375.50))
                .expireTime(Instant.now().plusMillis(10 * 60 * 1000))
                .updatedAt(Instant.now())
                .createdAt(Instant.now())
                .build()
                .toEntity();

        entityManager.persist(target);
        return target.getId();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRED)
    public Long txRequiredTest2(Integer id) {
        if (true) {
            throw new RuntimeException("GENERATED_ERR");
        }
        return queryFactory.update(remittance)
                .set(remittance.requestedTime, Instant.now())
                .set(remittance.updatedAt, Instant.now())
                .where(remittance.id.eq(id)
                        .and(remittance.requestedTime.isNull()))
                .execute();
    }

    public void clearEntityManager() {
        entityManager.clear();
    }
}
