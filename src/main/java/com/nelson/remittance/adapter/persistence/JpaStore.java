package com.nelson.remittance.adapter.persistence;

import static com.nelson.remittance.domain.BusinessException.Code.DUPLICATE_USER;
import static com.nelson.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static com.nelson.remittance.domain.BusinessException.Code.QUOTE_NOT_FOUND;
import static com.nelson.remittance.domain.BusinessException.Code.UNAUTHORIZED;

import com.nelson.remittance.application.Ports;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.Quote;
import com.nelson.remittance.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
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
        UserEntity user = em.find(UserEntity.class, integerId(id), LockModeType.PESSIMISTIC_WRITE);
        if (user == null) {
            throw new BusinessException(UNAUTHORIZED);
        }
        return user.toDomain();
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
    // 접수된 거래만 최신 시각·식별자 순으로 읽어 미접수 견적이 집계에 들어가지 않게 한다.
    public List<Quote> requestedQuotes(long userId) {
        return em
                .createQuery(
                        "select q from QuoteEntity q where q.user.id = :userId and q.requestedTime is not null "
                                + "order by q.requestedTime desc, q.id desc",
                        QuoteEntity.class)
                .setParameter("userId", integerId(userId))
                .getResultList()
                .stream()
                .map(QuoteEntity::toDomain)
                .toList();
    }
}
