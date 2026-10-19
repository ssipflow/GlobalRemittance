package com.nelson.remittance.adapter.persistence.repository;

import static com.nelson.remittance.domain.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static com.nelson.remittance.domain.exception.ErrorCode.LOCK_TIMEOUT;
import static com.nelson.remittance.domain.exception.ErrorCode.QUOTE_NOT_FOUND;

import com.nelson.remittance.adapter.persistence.entity.QuoteEntity;
import com.nelson.remittance.adapter.persistence.entity.UserEntity;
import com.nelson.remittance.application.port.repository.DailyTotal;
import com.nelson.remittance.application.port.repository.QuotePosition;
import com.nelson.remittance.application.port.repository.QuoteRepository;
import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.quote.Quote;
import com.nelson.remittance.domain.quote.QuotePolicy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
// 견적 저장·조회·집계를 담당한다.
public class JpaQuoteRepository implements QuoteRepository {
    private final EntityManager em;

    public JpaQuoteRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    // 견적과 소유자를 내부 모델로 변환하되 접수 여부와 만료 판단은 도메인에 맡긴다.
    public Optional<Quote> findQuote(long id) {
        return Optional.ofNullable(em.find(QuoteEntity.class, EntityIds.toInt(id)))
                .map(QuoteEntity::toDomain);
    }

    @Override
    // 키 조회는 항상 userId 조건을 포함해 사용자 간 키 충돌과 타인 견적 노출을 막는다.
    public Optional<Quote> findByIdempotencyKey(long userId, String idempotencyKey) {
        return em.createQuery(
                        "select q from QuoteEntity q where q.user.id = :userId "
                                + "and q.idempotencyKey = :key",
                        QuoteEntity.class)
                .setParameter("userId", EntityIds.toInt(userId))
                .setParameter("key", idempotencyKey)
                .getResultStream()
                .findFirst()
                .map(QuoteEntity::toDomain);
    }

    @Override
    // 새 견적은 계산 전체를 저장하고 기존 견적은 접수·수정 시각만 변경한다.
    public Quote saveQuote(Quote quote) {
        if (quote.id() == 0) {
            QuoteEntity entity =
                    new QuoteEntity(
                            quote,
                            em.getReference(UserEntity.class, EntityIds.toInt(quote.userId())));
            try {
                em.persist(entity);
                em.flush();
            } catch (PersistenceException error) {
                // 새 견적 INSERT는 외래키 검사로 사용자 행에 공유 잠금을 요청한다. 접수가 그 행을
                // 오래 쥐면 여기서도 lock_timeout이 나므로 lockUser와 같이 재시도 가능한 503으로 알린다.
                throw translateLockTimeout(error);
            }
            return entity.toDomain();
        }
        QuoteEntity entity = em.find(QuoteEntity.class, EntityIds.toInt(quote.id()));
        if (entity == null) {
            throw new BusinessException(QUOTE_NOT_FOUND);
        }
        // 접수는 상태만 갱신한다. 견적 시 확정한 환율·수수료를 현재 시세로 재계산하지 않는다.
        entity.markRequested(quote.requestedTime(), quote.idempotencyKey(), quote.updatedAt());
        try {
            em.flush();
        } catch (PersistenceException error) {
            // 사용자 잠금 안에서 직렬화되므로 정상 경로에서는 일어나지 않는다. 잠금 밖 쓰기 같은
            // 예외 상황에서도 한 키가 두 견적에 묶이지 않도록 유일 제약을 최종 방어로 둔다.
            if (PersistenceErrors.isUniqueViolation(error)) {
                throw new BusinessException(IDEMPOTENCY_KEY_REUSED);
            }
            throw translateLockTimeout(error);
        }
        return entity.toDomain();
    }

    // 락 대기 초과면 업무 오류(LOCK_TIMEOUT)로, 아니면 원래 예외를 그대로 돌려준다.
    private static RuntimeException translateLockTimeout(PersistenceException error) {
        return PersistenceErrors.isLockTimeout(error) ? new BusinessException(LOCK_TIMEOUT) : error;
    }

    @Override
    // 접수된 거래만 최신 시각·ID 순으로 읽는다. 경계 조건은 정렬 키와 같아 중복·누락이 없고,
    // 항상 userId 조건을 포함하므로 다른 사용자의 위치값으로도 타인 데이터에 접근할 수 없다.
    public List<Quote> requestedPage(long userId, QuotePosition after, int limit) {
        var query =
                em.createQuery(
                        "select q from QuoteEntity q where q.user.id = :userId and q.requestedTime is not null "
                                + (after == null
                                        ? ""
                                        : "and (q.requestedTime < :time or (q.requestedTime = :time and q.id < :id)) ")
                                + "order by q.requestedTime desc, q.id desc",
                        QuoteEntity.class);
        query.setParameter("userId", EntityIds.toInt(userId));
        if (after != null) {
            query.setParameter("time", after.requestedTime());
            query.setParameter("id", EntityIds.toInt(after.id()));
        }
        return query.setMaxResults(limit).getResultList().stream()
                .map(QuoteEntity::toDomain)
                .toList();
    }

    @Override
    // 건수와 합계를 한 JPQL로 구해 같은 스냅샷 값이 되게 하며, 합계가 없으면 0.00으로 정규화한다.
    public DailyTotal requestedDailyTotal(long userId, Instant from, Instant to) {
        Object[] row =
                em.createQuery(
                                "select count(q), sum(q.usdAmount) from QuoteEntity q where q.user.id = :userId "
                                        + "and q.requestedTime >= :from and q.requestedTime < :to",
                                Object[].class)
                        .setParameter("userId", EntityIds.toInt(userId))
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getSingleResult();
        BigDecimal sum = (BigDecimal) row[1];
        return new DailyTotal(
                ((Number) row[0]).intValue(),
                QuotePolicy.money(sum == null ? BigDecimal.ZERO : sum, "USD"));
    }

    @Override
    // 접수 시각이 [from, to)인 견적의 USD 합계를 DB에서 집계하고 결과가 없으면 0.00으로 정규화한다.
    public BigDecimal requestedUsdTotal(long userId, Instant from, Instant to) {
        BigDecimal sum =
                em.createQuery(
                                "select sum(q.usdAmount) from QuoteEntity q where q.user.id = :userId "
                                        + "and q.requestedTime >= :from and q.requestedTime < :to",
                                BigDecimal.class)
                        .setParameter("userId", EntityIds.toInt(userId))
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getSingleResult();
        return QuotePolicy.money(sum == null ? BigDecimal.ZERO : sum, "USD");
    }
}
