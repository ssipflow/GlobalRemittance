package com.nelson.remittance.adapter.persistence.repository;

import static com.nelson.remittance.domain.exception.ErrorCode.DUPLICATE_USER;
import static com.nelson.remittance.domain.exception.ErrorCode.LOCK_TIMEOUT;
import static com.nelson.remittance.domain.exception.ErrorCode.UNAUTHORIZED;

import com.nelson.remittance.adapter.persistence.entity.UserEntity;
import com.nelson.remittance.application.port.repository.AccountRepository;
import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
// 계정 저장과 사용자 행 잠금을 담당한다.
public class JpaAccountRepository implements AccountRepository {
    private final EntityManager em;

    public JpaAccountRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    // 필요한 사용자만 조회해 내부 모델로 변환한다. 트랜잭션은 사용 사례의 포트 경계가 제공한다.
    public Optional<User> findUser(long id) {
        return Optional.ofNullable(em.find(UserEntity.class, EntityIds.toInt(id)))
                .map(UserEntity::toDomain);
    }

    @Override
    // 필요한 사용자만 조회해 내부 모델로 변환한다. 가입·로그인은 느린 해시 계산 동안 연결을 쥐지 않으려고
    // 사용 사례 트랜잭션 없이 호출하므로, 결과 스트림을 읽는 동안만 유지되는 읽기 전용 트랜잭션을 둔다.
    // 이미 트랜잭션 안에서 호출되면 그 트랜잭션에 참여한다.
    @Transactional(readOnly = true)
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
            // 대기 한도(remittance.lock-timeout-ms)는 H2의 JDBC URL LOCK_TIMEOUT 또는
            // postgres 프로파일의 SET lock_timeout이 정한다. Hibernate의
            // jakarta.persistence.lock.timeout 힌트는 H2 방언이 'for update wait'를 내지 않아 무시된다.
            user = em.find(UserEntity.class, EntityIds.toInt(id), LockModeType.PESSIMISTIC_WRITE);
        } catch (PersistenceException error) {
            // 다른 접수가 오래 잠금을 쥐면 500이 아닌 재시도 가능한 업무 오류로 알린다.
            if (PersistenceErrors.isLockTimeout(error)) {
                throw new BusinessException(LOCK_TIMEOUT);
            }
            throw error;
        }
        if (user == null) {
            throw new BusinessException(UNAUTHORIZED);
        }
        return user.toDomain();
    }

    @Override
    // 사용 사례(가입)가 느린 해시 계산 동안 연결을 쥐지 않도록 저장만 짧은 트랜잭션으로 감싼다.
    // 저장을 즉시 반영해 동시 가입의 유일 제약 실패를 업무 오류로 바꾼다.
    @Transactional
    public User saveUser(User user) {
        UserEntity entity = new UserEntity(user);
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException error) {
            // 사전 중복 조회만으로 동시 가입을 막을 수 없다. DB 유일 제약을 최종 방어로 둔다.
            if (PersistenceErrors.isUniqueViolation(error)) {
                throw new BusinessException(DUPLICATE_USER);
            }
            throw error;
        }
        return entity.toDomain();
    }
}
