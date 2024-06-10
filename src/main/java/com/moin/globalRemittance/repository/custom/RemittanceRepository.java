package com.moin.globalRemittance.repository.custom;

import com.moin.globalRemittance.dto.IRemittanceDto;
import com.moin.globalRemittance.entities.QRemittance;
import com.moin.globalRemittance.entities.Remittance;
import com.moin.globalRemittance.repository.model.RemittanceDto;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class RemittanceRepository {

    private final JPAQueryFactory queryFactory;
    private final EntityManager entityManager;

    private final QRemittance remittance = QRemittance.remittance;

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Integer createRemittance(IRemittanceDto dto) {
        Remittance remittance = RemittanceDto.builder()
                .userId(dto.getUserId())
                .sourceAmount(dto.getSourceAmount())
                .targetAmount(dto.getTargetAmount())
                .targetCurrency(dto.getTargetCurrency())
                .exchangeRate(dto.getExchangeRate())
                .fixedFee(dto.getFixedFee())
                .ratedFee(dto.getRatedFee())
                .feeRatio(dto.getFeeRatio())
                .usdAmount(dto.getUsdAmount())
                .usdExchangeRate(dto.getUsdExchangeRate())
                .expireTime(dto.getExpireTime())
                .requestedTime(dto.getRequestedTime())
                .updatedAt(Instant.now())
                .createdAt(Instant.now())
                .build()
                .toEntity();

        entityManager.persist(remittance);
        return remittance.getId();
    }

    public IRemittanceDto getRemittanceById(Integer id) {
        return queryFactory
                .select(Projections.fields(RemittanceDto.class,
                        remittance.id.as("id"),
                        remittance.user.id.as("userId"),
                        remittance.sourceAmount.as("sourceAmount"),
                        remittance.targetAmount.as("targetAmount"),
                        remittance.targetCurrency.as("targetCurrency"),
                        remittance.exchangeRate.as("exchangeRate"),
                        remittance.fixedFee.as("fixedFee"),
                        remittance.ratedFee.as("ratedFee"),
                        remittance.feeRatio.as("feeRatio"),
                        remittance.usdAmount.as("usdAmount"),
                        remittance.usdExchangeRate.as("usdExchangeRate"),
                        remittance.expireTime.as("expireTime"),
                        remittance.requestedTime.as("requestedTime")
                ))
                .from(remittance)
                .where(remittance.id.eq(id))
                .fetchOne();
    }

    public IRemittanceDto getWaitingRemittanceById(Integer id) {
        return queryFactory
                .select(Projections.fields(RemittanceDto.class,
                        remittance.id.as("id"),
                        remittance.user.id.as("userId"),
                        remittance.sourceAmount.as("sourceAmount"),
                        remittance.targetAmount.as("targetAmount"),
                        remittance.targetCurrency.as("targetCurrency"),
                        remittance.exchangeRate.as("exchangeRate"),
                        remittance.fixedFee.as("fixedFee"),
                        remittance.ratedFee.as("ratedFee"),
                        remittance.feeRatio.as("feeRatio"),
                        remittance.usdAmount.as("usdAmount"),
                        remittance.usdExchangeRate.as("usdExchangeRate"),
                        remittance.expireTime.as("expireTime"),
                        remittance.requestedTime.as("requestedTime")
                ))
                .from(remittance)
                .where(remittance.id.eq(id)
                        .and(remittance.requestedTime.isNull()))
                .fetchOne();
    }

    public List<IRemittanceDto> getDailyRemittanceListByUser(Integer userId) {
        LocalDateTime startOfDayKST = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime endOfDayKST = startOfDayKST.plusDays(1);

        Instant startOfDay = startOfDayKST.atZone(ZoneId.systemDefault()).toInstant();
        Instant endOfDay = endOfDayKST.atZone(ZoneId.systemDefault()).toInstant();

        List<RemittanceDto> remittanceList = queryFactory
                .select(Projections.fields(RemittanceDto.class,
                        remittance.id.as("id"),
                        remittance.user.id.as("userId"),
                        remittance.sourceAmount.as("sourceAmount"),
                        remittance.targetAmount.as("targetAmount"),
                        remittance.targetCurrency.as("targetCurrency"),
                        remittance.exchangeRate.as("exchangeRate"),
                        remittance.fixedFee.as("fixedFee"),
                        remittance.ratedFee.as("ratedFee"),
                        remittance.feeRatio.as("feeRatio"),
                        remittance.usdAmount.as("usdAmount"),
                        remittance.usdExchangeRate.as("usdExchangeRate"),
                        remittance.expireTime.as("expireTime"),
                        remittance.requestedTime.as("requestedTime")
                ))
                .from(remittance)
                .where(remittance.user.id.eq(userId)
                        .and(remittance.requestedTime.between(startOfDay, endOfDay)))
                .fetch();

        return remittanceList.stream()
                .map(dto -> (IRemittanceDto) dto)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Long updateRequestedTime(Integer id) {
        return queryFactory.update(remittance)
                .set(remittance.requestedTime, Instant.now())
                .set(remittance.updatedAt, Instant.now())
                .where(remittance.id.eq(id)
                        .and(remittance.requestedTime.isNull()))
                .execute();
    }
}
