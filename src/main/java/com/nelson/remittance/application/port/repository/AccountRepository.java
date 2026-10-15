package com.nelson.remittance.application.port.repository;

import com.nelson.remittance.domain.user.User;
import java.util.Optional;

// 계정 조회·저장과 사용자 행 잠금의 계약이다. 잠금은 호출 트랜잭션까지 유지된다.
public interface AccountRepository {
    Optional<User> findUser(long id);

    Optional<User> findUser(String userId);

    User lockUser(long id);

    User saveUser(User user);
}
