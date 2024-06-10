package com.moin.globalRemittance.repository;

import com.moin.globalRemittance.dto.IRemittanceDto;
import com.moin.globalRemittance.repository.custom.RemittanceRepository;
import com.moin.globalRemittance.service.TxTestService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Transactional이 선언된 메서드를 호출하면 트랜잭션이 제대로 적용되지 않을 수 있습니다. 이것은 Spring AOP의 한계로, 프록시 객체를 통해 트랜잭션이 관리되기 때문입니다.
 *
 * 이 문제를 해결하기 위해서는 다음과 같은 방법을 사용할 수 있습니다:
 *
 * 1. 별도의 서비스 클래스로 분리: 트랜잭션이 필요한 메서드를 별도의 서비스 클래스로 분리하여 호출합니다.
 * 2. Spring의 TransactionTemplate 사용: 트랜잭션을 프로그래밍 방식으로 제어합니다.
 *
 * 여기서는 첫 번째 방법인 별도의 서비스 클래스로 분리하는 예를 보여드리겠습니다.
 *
 * 1. 별도의 서비스 클래스로 분리하기
 * TxTestService라는 새로운 클래스를 생성하여 트랜잭션이 필요한 메서드를 포함합니다.
 *
 * 2. 테스트 클래스에서 서비스 클래스를 사용하기
 * 기존의 테스트 클래스에서 새로 생성한 서비스 클래스를 주입받아 사용합니다.
 */
@SpringBootTest
public class TxTestRepositoryTest {

    private final static Logger log = LoggerFactory.getLogger(TxTestRepositoryTest.class);

    @Autowired
    private TxTestService txTestService;

    @Autowired
    private RemittanceRepository remittanceRepository;

    @Test
    void testTransactionRequiresNew() {
        Integer id = null;
        try {
            txTestService.testTransaction1();
        } catch (RuntimeException e) {
            log.error(e.getMessage());
            id = Integer.parseInt(e.getMessage());
        }
        IRemittanceDto remittance = remittanceRepository.getRemittanceById(id);
        if (remittance.getRequestedTime() != null) {
            log.info("inserted: {}", remittance);
        }
        assertNotNull(remittance, "Inserted Data must be committed");
    }

    @Test
    void testTransactionRequired() {
        Integer id = null;
        try {
            txTestService.testTransaction2();
        } catch (RuntimeException e) {
            log.error(e.getMessage());
            id = Integer.parseInt(e.getMessage());
        }
        IRemittanceDto remittance = remittanceRepository.getRemittanceById(id);
        if (remittance == null) {
            log.info("no data for ID: {}", id);
        }
        assertNull(remittance, "Inserted Data must be rollback");
    }
}
