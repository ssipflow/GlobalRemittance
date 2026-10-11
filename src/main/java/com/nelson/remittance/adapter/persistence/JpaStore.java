package com.nelson.remittance.adapter.persistence;

import static com.nelson.remittance.domain.BusinessException.Code.DUPLICATE_USER;
import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static com.nelson.remittance.domain.BusinessException.Code.LOCK_TIMEOUT;
import static com.nelson.remittance.domain.BusinessException.Code.QUOTE_NOT_FOUND;
import static com.nelson.remittance.domain.BusinessException.Code.UNAUTHORIZED;

import com.nelson.remittance.application.Ports;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.QuotePolicy;
import com.nelson.remittance.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

@Repository
// JPA 조회와 모델 변환을 담당하며 DB 잠금과 유일 제약으로 동시 처리 경계를 지킨다.
public class JpaStore implements Ports.Accounts, Ports.Quotes {
    private final EntityManager em;

    public JpaStore(EntityManager em) {
        this.em = em;
    }

    // 외부 long 식별자가 DB INT 범위를 넘거나 양수가 아니면 변환 전에 거부한다.
    private static int integerId(long id) {
        if (id <= 0 || id > Integer.MAX_VALUE) {
            throw new BusinessException(INVALID_REQUEST);
        }
        return (int) id;
    }

    @Override
    // 필요한 사용자만 조회해 내부 모델로 변환한다. 트랜잭션은 사용 사례의 포트 경계가 제공한다.
    public Optional<User> findUser(long id) {
        return Optional.ofNullable(em.find(UserEntity.class, integerId(id)))
                .map(UserEntity::toDomain);
    }

    @Override
    // 필요한 사용자만 조회해 내부 모델로 변환한다. 트랜잭션은 사용 사례의 포트 경계가 제공한다.
    public Optional<User> findUser(String userId) {
        return em.createQuery(
                        "select u from UserEntity u where u.userId = :userId", UserEntity.class)
                .setParameter("userId", userId)
                .getResultStream()
                .findFirst()
                .map(UserEntity::toDomain);
    }

    @Override
    // 사용자 행의 배타 잠금을 잡아 같은 사용자의 다른 견적 접수도 직렬화한다.
    public User lockUser(long id) {
        UserEntity user;
        try {
            // 대기 한도는 JDBC URL의 LOCK_TIMEOUT(remittance.lock-timeout-ms)이 정한다. Hibernate의
            // jakarta.persistence.lock.timeout 힌트는 H2 방언이 'for update wait'를 내지 않아 무시된다.
            user = em.find(UserEntity.class, integerId(id), LockModeType.PESSIMISTIC_WRITE);
        } catch (PersistenceException error) {
            // 다른 접수가 오래 잠금을 쥐면 500이 아닌 재시도 가능한 업무 오류로 알린다.
            if (isLockTimeout(error)) {
                throw new BusinessException(LOCK_TIMEOUT);
            }
            throw error;
        }
        if (user == null) {
            throw new BusinessException(UNAUTHORIZED);
        }
        return user.toDomain();
    }

    // 락 대기 초과를 JPA 예외 또는 DB 상태 코드(H2·표준 HYT00, PostgreSQL 55P03)로 식별한다.
    private static boolean isLockTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
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

    @Override
    // 저장을 즉시 반영해 동시 가입의 유일 제약 실패를 업무 오류로 바꾼다.
    public User saveUser(User user) {
        UserEntity entity = new UserEntity(user);
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException error) {
            // 사전 중복 조회만으로 동시 가입을 막을 수 없다. DB 유일 제약을 최종 방어로 둔다.
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())) {
                    throw new BusinessException(DUPLICATE_USER);
                }
            }
            throw error;
        }
        return entity.toDomain();
    }

    @Override
    // 견적과 소유자를 내부 모델로 변환하되 접수 여부와 만료 판단은 도메인에 맡긴다.
    public Optional<Quote> findQuote(long id) {
        return Optional.ofNullable(em.find(QuoteEntity.class, integerId(id)))
                .map(QuoteEntity::toDomain);
    }

    @Override
    // 새 견적은 계산 전체를 저장하고 기존 견적은 접수·수정 시각만 변경한다.
    public Quote saveQuote(Quote quote) {
        if (quote.id() == 0) {
            QuoteEntity entity =
                    new QuoteEntity(
                            quote, em.getReference(UserEntity.class, integerId(quote.userId())));
            em.persist(entity);
            em.flush();
            return entity.toDomain();
        }
        QuoteEntity entity = em.find(QuoteEntity.class, integerId(quote.id()));
        if (entity == null) {
            throw new BusinessException(QUOTE_NOT_FOUND);
        }
        // 접수는 상태만 갱신한다. 견적 시 확정한 환율·수수료를 현재 시세로 재계산하지 않는다.
        entity.requestedTime = quote.requestedTime();
        entity.updatedAt = quote.updatedAt();
        em.flush();
        return entity.toDomain();
    }

    @Override
    // 접수된 거래만 최신 시각·ID 순으로 읽는다. 경계 조건은 정렬 키와 같아 중복·누락이 없고,
    // 항상 userId 조건을 포함하므로 다른 사용자의 위치값으로도 타인 데이터에 접근할 수 없다.
    public List<Quote> requestedPage(long userId, Position after, int limit) {
        var query =
                em.createQuery(
                        "select q from QuoteEntity q where q.user.id = :userId and q.requestedTime is not null "
                                + (after == null
                                        ? ""
                                        : "and (q.requestedTime < :time or (q.requestedTime = :time and q.id < :id)) ")
                                + "order by q.requestedTime desc, q.id desc",
                        QuoteEntity.class);
        query.setParameter("userId", integerId(userId));
        if (after != null) {
            query.setParameter("time", after.requestedTime());
            query.setParameter("id", integerId(after.id()));
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
                        .setParameter("userId", integerId(userId))
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
                        .setParameter("userId", integerId(userId))
                        .setParameter("from", from)
                        .setParameter("to", to)
                        .getSingleResult();
        return QuotePolicy.money(sum == null ? BigDecimal.ZERO : sum, "USD");
    }
}
