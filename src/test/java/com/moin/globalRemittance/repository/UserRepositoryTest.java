package com.moin.globalRemittance.repository;

import com.moin.globalRemittance.dto.IUserDto;
import com.moin.globalRemittance.repository.custom.UserRepository;
import com.moin.globalRemittance.repository.model.UserDto;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
public class UserRepositoryTest {

    private Logger log = LoggerFactory.getLogger(UserRepositoryTest.class);

    @Autowired
    private UserRepository userRepository;

    @Test
    public void testGetUserByEmail() {
        String targetEmail = "reg@email.com";

        IUserDto user = userRepository.getUserByEmail(targetEmail);
        assertThat(user).isNotNull();
        assertThat(user.getUserId()).isEqualTo(targetEmail);
        assertThat(user.getName()).isEqualTo("mock");
        assertThat(user.getIdType()).isEqualTo("REG_NO");

        log.info(user.toString());
    }

    @Test
    public void testCreateUser() {
        IUserDto dto = UserDto.builder()
                .userId("userRepoTest@email.com")
                .password("test1234")
                .name("tester")
                .idType("BUSINESS_NO")
                .idValue("43212345968")
                .build();

        userRepository.createUser(dto);

        IUserDto user = userRepository.getUserByEmail(dto.getUserId());
        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo(2);
        assertThat(user.getUserId()).isEqualTo(dto.getUserId());
        assertThat(user.getName()).isEqualTo(user.getName());
        assertThat(user.getIdType()).isEqualTo(user.getIdType());

        log.info(user.toString());
    }
}

