package com.nelson.remittance.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nelson.remittance.adapter.persistence.tx.RollbackSafeTransactionManager;
import com.nelson.remittance.application.user.UserUseCases;
import com.nelson.remittance.domain.exception.BusinessException;
import com.nelson.remittance.domain.exception.ErrorCode;
import com.nelson.remittance.domain.user.User;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

// 롤백 자체가 실패하는 상황을 실제 PostgreSQL에서 만들어 RollbackSafeTransactionManager의 보장을 검증한다.
// H2에서는 Mockito 대역으로만 재현했다(RollbackFailureTest). 여기서는 트랜잭션 도중 DB가 이 연결의 백엔드를
// 종료(pg_terminate_backend)해 실제 JDBC 롤백이 실패하게 만든다.
// Docker가 필요하며 없으면 건너뛰지 않고 실패한다. postgresTest로만 실행된다.
@Tag("postgres")
@SpringBootTest
@ActiveProfiles({"test", "postgres"})
@ExtendWith(OutputCaptureExtension.class)
class PostgresRollbackFailureTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        // 클래스당 컨테이너 하나를 컨텍스트 생성 전에 띄우며 종료는 Testcontainers(Ryuk)가 맡는다.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // 트랜잭션 안에서 쓰기를 한 뒤 자기 연결을 DB가 끊게 하고 업무 예외로 끝낸다. 롤백은 죽은 연결에서 시도된다.
    static class Probe {
        @Autowired UserUseCases users;
        @PersistenceContext EntityManager em;

        // 가입(쓰기)을 같은 트랜잭션에 포함한 뒤 연결을 끊고 LOCK_TIMEOUT으로 실패한다.
        @Transactional
        public void writeKillAndFail(String userId) throws SQLException, InterruptedException {
            writeAndKill(userId);
            throw new BusinessException(ErrorCode.LOCK_TIMEOUT);
        }

        // 트랜잭션 매니저를 바꿔 쓰는 대조 실험용이다. 호출자가 연 트랜잭션 안에서 쓰기와 연결 종료까지만 한다.
        public void writeAndKill(String userId) throws SQLException, InterruptedException {
            users.signup(
                    userId, "example-password", "롤백 실패 사용자", User.IdentityType.REG_NO, "id-value");
            em.flush();
            int pid =
                    ((Number) em.createNativeQuery("select pg_backend_pid()").getSingleResult())
                            .intValue();
            terminateAndWait(pid);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @Autowired Probe probe;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManagerFactory emf;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    // 다른 연결에서 백엔드를 종료하고 사라질 때까지 기다린다. 종료 신호는 비동기라 기다리지 않으면 롤백이 먼저 성공할 수 있다.
    static void terminateAndWait(int pid) throws SQLException, InterruptedException {
        try (Connection admin =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                var terminate = admin.prepareStatement("select pg_terminate_backend(?)");
                var alive =
                        admin.prepareStatement(
                                "select count(*) from pg_stat_activity where pid = ?")) {
            terminate.setInt(1, pid);
            assertTrue(terminate.executeQuery().next());
            alive.setInt(1, pid);
            for (int i = 0; i < 100; i++) {
                var rs = alive.executeQuery();
                rs.next();
                if (rs.getInt(1) == 0) {
                    return;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("백엔드가 종료되지 않았다: " + pid);
        }
    }

    private int countUsers(String userId) {
        return jdbc.queryForObject(
                "select count(*) from users where user_id = ?", Integer.class, userId);
    }

    // 끊긴 연결을 풀이 정리하는 동안 첫 시도는 실패할 수 있어, 제한된 재시도 안에 풀이 복구되는 계약만 단언한다.
    private void assertPoolRecovers() throws InterruptedException {
        boolean recovered = false;
        for (int attempt = 0; attempt < 10 && !recovered; attempt++) {
            try {
                assertEquals(1, jdbc.queryForObject("select 1", Integer.class));
                recovered = true;
            } catch (RuntimeException e) {
                Thread.sleep(200);
            }
        }
        assertTrue(recovered, "10회 안에 풀이 복구되어야 한다");
        for (int i = 0; i < 5; i++) {
            assertEquals(1, jdbc.queryForObject("select 1", Integer.class));
        }
    }

    @Test
    // 롤백이 실제로 실패해도(경고 로그가 그 증거) 호출자는 원래 업무 예외를 받고, 쓰기는 남지 않으며,
    // 트랜잭션 리소스와 풀 연결이 정리되어 이후 요청이 정상 처리된다.
    void realRollbackFailureKeepsBusinessErrorAndLeavesNoState(CapturedOutput output)
            throws Exception {
        assertInstanceOf(RollbackSafeTransactionManager.class, manager);
        String userId = "rb-" + System.nanoTime() + "@example.com";

        BusinessException error =
                assertThrows(BusinessException.class, () -> probe.writeKillAndFail(userId));

        assertEquals(ErrorCode.LOCK_TIMEOUT, error.code());
        // 롤백 실패가 실제로 일어났다는 증거다. 원래 예외 보존이 우연히 롤백 성공 때문이 아님을 보인다.
        // 원인이 연결 종료(관리자 종료 SQLState 57P01)임까지 함께 확인한다.
        String log = output.getAll();
        assertTrue(log.contains("트랜잭션 롤백 실패를 무시하고 원래 예외를 전달한다"), "롤백 실패 로그가 있어야 한다");
        assertTrue(log.contains("sqlState=57P01"), "연결 종료가 롤백 실패 원인이어야 한다");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        // 죽은 연결을 풀이 정리한 뒤에 확인한다. DB는 연결이 죽을 때 트랜잭션을 롤백했으므로 가입 쓰기가 남아서는 안 된다.
        assertPoolRecovers();
        assertEquals(0, countUsers(userId));
        // 실패한 연결이 풀에서 점유된 채 남지 않는다.
        var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        assertEquals(0, pool.getActiveConnections());
    }

    @Test
    // 대조: 보존 장치가 없는 기본 JpaTransactionManager는 같은 상황에서 롤백 실패 예외로 업무 예외를 덮는다.
    // RollbackSafeTransactionManager가 필요한 이유를 실제 DB에서 확인한다(H2 쪽은 대역으로만 확인했다).
    void plainManagerMasksBusinessErrorOnRealRollbackFailure() throws Exception {
        String userId = "rb-plain-" + System.nanoTime() + "@example.com";
        var template = new TransactionTemplate(new JpaTransactionManager(emf));

        RuntimeException masked =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                template.executeWithoutResult(
                                        status -> {
                                            try {
                                                probe.writeAndKill(userId);
                                            } catch (SQLException | InterruptedException e) {
                                                throw new IllegalStateException(e);
                                            }
                                            throw new BusinessException(ErrorCode.LOCK_TIMEOUT);
                                        }));

        // 원래 업무 예외 대신 롤백 실패가 데이터 접근 예외로 전파되며, 원인 연쇄에 DB 연결 종료(SQLException)가 있다.
        assertFalse(masked instanceof BusinessException, "기본 매니저는 원래 업무 예외를 덮어야 한다: " + masked);
        assertInstanceOf(DataAccessException.class, masked);
        boolean sqlCause = false;
        for (Throwable cause = masked; cause != null; cause = cause.getCause()) {
            sqlCause |= cause instanceof SQLException;
        }
        assertTrue(sqlCause, "원인 연쇄에 SQLException이 있어야 한다: " + masked);
        assertPoolRecovers();
        assertEquals(0, countUsers(userId));
    }
}
