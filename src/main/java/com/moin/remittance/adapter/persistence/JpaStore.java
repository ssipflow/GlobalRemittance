package com.moin.remittance.adapter.persistence;

import static com.moin.remittance.domain.BusinessException.Code.DUPLICATE_USER;
import static com.moin.remittance.domain.BusinessException.Code.INVALID_REQUEST;
import static com.moin.remittance.domain.BusinessException.Code.QUOTE_NOT_FOUND;
import static com.moin.remittance.domain.BusinessException.Code.UNAUTHORIZED;

import com.moin.remittance.application.Ports;
import com.moin.remittance.domain.BusinessException;
import com.moin.remittance.domain.Quote;
import com.moin.remittance.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

@Repository
public class JpaStore implements Ports.Accounts, Ports.Quotes {
    private final EntityManager em;

    public JpaStore(EntityManager em) {
        this.em = em;
    }

    private static int integerId(long id) {
        if (id <= 0 || id > Integer.MAX_VALUE) {
            throw new BusinessException(INVALID_REQUEST);
        }
        return (int) id;
    }

    @Override
    public Optional<User> findUser(long id) {
        return Optional.ofNullable(em.find(UserEntity.class, integerId(id)))
                .map(UserEntity::toDomain);
    }

    @Override
    public Optional<User> findUser(String userId) {
        return em.createQuery(
                        "select u from UserEntity u where u.userId = :userId", UserEntity.class)
                .setParameter("userId", userId)
                .getResultStream()
                .findFirst()
                .map(UserEntity::toDomain);
    }

    @Override
    public User lockUser(long id) {
        UserEntity user = em.find(UserEntity.class, integerId(id), LockModeType.PESSIMISTIC_WRITE);
        if (user == null) {
            throw new BusinessException(UNAUTHORIZED);
        }
        return user.toDomain();
    }

    @Override
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
    public Optional<Quote> findQuote(long id) {
        return Optional.ofNullable(em.find(QuoteEntity.class, integerId(id)))
                .map(QuoteEntity::toDomain);
    }

    @Override
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
