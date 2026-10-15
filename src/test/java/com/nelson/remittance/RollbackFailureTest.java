package com.nelson.remittance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nelson.remittance.adapter.persistence.RollbackSafeTransactionManager;
import com.nelson.remittance.application.TransferUseCases;
import com.nelson.remittance.application.UserUseCases;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.BusinessException.Code;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// @Transactional 전환 후에도 롤백 실패가 원래 업무 예외를 덮지 않는지, 유스케이스에 프록시가 걸리는지 고정한다.
// 실험 결과: 기본 JpaTransactionManager는 롤백 예외로 원래 예외를 덮으므로(아래 첫 테스트) 보존 장치가 필요하다.
@SpringBootTest
@ActiveProfiles("test")
class RollbackFailureTest {
    @Autowired TransferUseCases transfers;
    @Autowired UserUseCases users;
    @Autowired PlatformTransactionManager manager;

    static class Service {
        @Transactional
        public void failWithBusiness() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw new BusinessException(Code.LOCK_TIMEOUT);
        }

        @Transactional
        public void failWithOther() {
            throw new IllegalStateException("boom");
        }
    }

    // 롤백 시 EntityTransaction.rollback이 실패하는 대역 EntityManagerFactory.
    static EntityManagerFactory failingRollbackFactory(EntityManager[] holder) {
        var factory = mock(EntityManagerFactory.class);
        var em = mock(EntityManager.class);
        var tx = mock(EntityTransaction.class);
        when(factory.createEntityManager()).thenReturn(em);
        when(em.getTransaction()).thenReturn(tx);
        when(em.isOpen()).thenReturn(true);
        // JpaTransactionManager는 활성 트랜잭션일 때만 롤백을 호출한다.
        when(tx.isActive()).thenReturn(true);
        doThrow(new PersistenceException("rollback exception")).when(tx).rollback();
        holder[0] = em;
        return factory;
    }

    // @Configuration을 붙이면 @SpringBootTest가 이 중첩 클래스를 앱 설정으로 오인하므로 직접 등록하는 lite 설정으로 둔다.
    @EnableTransactionManagement
    static class Config {
        static JpaTransactionManager manager;

        @Bean
        Service service() {
            return new Service();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return manager;
        }
    }

    static AnnotationConfigApplicationContext context(JpaTransactionManager manager) {
        Config.manager = manager;
        return new AnnotationConfigApplicationContext(Config.class);
    }

    @Test
    // 재현: 기본 매니저는 롤백 실패 예외로 원래 BusinessException을 덮는다. 이 동작이 보존 장치를 요구하는 근거다.
    void plainManagerMasksOriginalExceptionWhenRollbackFails() {
        var em = new EntityManager[1];
        var plain = new JpaTransactionManager(failingRollbackFactory(em));
        try (var ctx = context(plain)) {
            var service = ctx.getBean(Service.class);
            assertTrue(AopUtils.isAopProxy(service));
            assertThrows(JpaSystemException.class, service::failWithBusiness);
        }
    }

    @Test
    // 롤백이 실패해도 호출자는 원래 예외를 받고 트랜잭션 리소스는 정리된다. Spring 동작이 바뀌어도 이 계약을 지킨다.
    void rollbackFailureKeepsOriginalExceptionAndCleansUp() {
        var em = new EntityManager[1];
        var safe = new RollbackSafeTransactionManager(failingRollbackFactory(em));
        try (var ctx = context(safe)) {
            var service = ctx.getBean(Service.class);
            var business = assertThrows(BusinessException.class, service::failWithBusiness);
            assertEquals(Code.LOCK_TIMEOUT, business.code());
            // 업무 예외 외의 원래 예외도 보존한다. 이전 TransactionalWork는 BusinessException만 보존했다.
            assertThrows(IllegalStateException.class, service::failWithOther);
            verify(em[0], org.mockito.Mockito.times(2)).close();
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        }
    }

    @Test
    // 앱의 트랜잭션 매니저는 단일 빈이며 롤백 보존 구현으로 교체되어 있다.
    void applicationUsesRollbackSafeManager() {
        assertInstanceOf(RollbackSafeTransactionManager.class, manager);
    }

    @Test
    // 유스케이스가 CGLIB 프록시이고 모든 업무 메서드에 트랜잭션 속성이 있어 실제로 트랜잭션이 걸린다.
    void useCasesAreTransactionalProxies() throws Exception {
        assertTrue(AopUtils.isCglibProxy(transfers));
        assertTrue(AopUtils.isCglibProxy(users));
        var source = new AnnotationTransactionAttributeSource();
        Method[] methods = {
            TransferUseCases.class.getMethod("quote", long.class, BigDecimal.class, String.class),
            TransferUseCases.class.getMethod("request", long.class, long.class, String.class),
            TransferUseCases.class.getMethod(
                    "history",
                    long.class,
                    com.nelson.remittance.application.port.QuotePosition.class,
                    int.class),
            UserUseCases.class.getMethod(
                    "signup",
                    String.class,
                    String.class,
                    String.class,
                    com.nelson.remittance.domain.User.IdentityType.class,
                    String.class),
            UserUseCases.class.getMethod("login", String.class, String.class)
        };
        for (Method method : methods) {
            var attribute = source.getTransactionAttribute(method, method.getDeclaringClass());
            assertNotNull(attribute, method.getName());
            assertFalse(attribute.isReadOnly(), method.getName());
        }
    }

    @Nested
    @SpringBootTest(properties = "spring.transaction.default-timeout=7")
    @ActiveProfiles("test")
    // 직접 등록한 매니저에도 Boot의 spring.transaction.* 설정이 적용되는지 고정한다.
    class BootTransactionSettings {
        @Autowired PlatformTransactionManager configured;

        @Test
        void defaultTimeoutPropertyIsApplied() {
            assertInstanceOf(RollbackSafeTransactionManager.class, configured);
            assertEquals(7, ((JpaTransactionManager) configured).getDefaultTimeout());
        }
    }
}
