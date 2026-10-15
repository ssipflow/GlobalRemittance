package com.nelson.remittance.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nelson.remittance.domain.BusinessException;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

// DB 예외 판별과 식별자 변환 규칙을 Spring·DB 없이 합성 예외로 검증한다.
class PersistenceErrorsTest {

    @Test
    void lockTimeoutRecognizesJpaExceptions() {
        assertThat(PersistenceErrors.isLockTimeout(new LockTimeoutException("t"))).isTrue();
        assertThat(PersistenceErrors.isLockTimeout(new PessimisticLockException("p"))).isTrue();
    }

    @Test
    void lockTimeoutRecognizesSqlStatesAnywhereInCauseChain() {
        for (String state : new String[] {"HYT00", "55P03"}) {
            var wrapped =
                    new PersistenceException(
                            new RuntimeException(new SQLException("timeout", state)));
            assertThat(PersistenceErrors.isLockTimeout(wrapped)).isTrue();
        }
    }

    @Test
    void lockTimeoutRejectsUnrelatedErrors() {
        assertThat(PersistenceErrors.isLockTimeout(new RuntimeException("x"))).isFalse();
        assertThat(
                        PersistenceErrors.isLockTimeout(
                                new PersistenceException(new SQLException("x", "23505"))))
                .isFalse();
        assertThat(PersistenceErrors.isLockTimeout(new PersistenceException(new SQLException("x"))))
                .isFalse();
    }

    @Test
    void causeChainWalkTerminatesOnSelfReference() {
        Throwable selfCaused =
                new RuntimeException("loop") {
                    @Override
                    public synchronized Throwable getCause() {
                        return this;
                    }
                };
        assertThat(PersistenceErrors.isLockTimeout(selfCaused)).isFalse();
        assertThat(PersistenceErrors.isUniqueViolation(selfCaused)).isFalse();
    }

    @Test
    void uniqueViolationRequiresConstraintViolationWithState23505() {
        var unique =
                new ConstraintViolationException("dup", new SQLException("dup", "23505"), "uk");
        assertThat(PersistenceErrors.isUniqueViolation(unique)).isTrue();
        assertThat(PersistenceErrors.isUniqueViolation(new PersistenceException(unique))).isTrue();

        var otherState =
                new ConstraintViolationException("fk", new SQLException("fk", "23503"), "fk");
        assertThat(PersistenceErrors.isUniqueViolation(new PersistenceException(otherState)))
                .isFalse();
        assertThat(
                        PersistenceErrors.isUniqueViolation(
                                new PersistenceException(new SQLException("dup", "23505"))))
                .isFalse();
    }

    @Test
    void toIntAcceptsPositiveIntRange() {
        assertThat(EntityIds.toInt(1)).isEqualTo(1);
        assertThat(EntityIds.toInt(Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void toIntRejectsNonPositiveAndOverflow() {
        for (long id : new long[] {0, -1, (long) Integer.MAX_VALUE + 1}) {
            assertThatThrownBy(() -> EntityIds.toInt(id))
                    .isInstanceOfSatisfying(
                            BusinessException.class,
                            e ->
                                    assertThat(e.code())
                                            .isEqualTo(BusinessException.Code.INVALID_REQUEST));
        }
    }
}
