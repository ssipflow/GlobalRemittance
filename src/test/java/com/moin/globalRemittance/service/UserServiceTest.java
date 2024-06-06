package com.moin.globalRemittance.service;

import com.moin.globalRemittance.dto.IUserDto;
import com.moin.globalRemittance.exception.UnauthorizedException;
import com.moin.globalRemittance.exception.UserException;
import com.moin.globalRemittance.repository.custom.UserRepository;
import com.moin.globalRemittance.service.model.UserDto;
import com.moin.globalRemittance.utils.AESUtil;
import com.moin.globalRemittance.utils.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@SpringBootTest
public class UserServiceTest {

    private Logger log = LoggerFactory.getLogger(UserServiceTest.class);

    @Autowired
    private UserService userService;

    @MockBean
    UserRepository userRepository;

    @MockBean
    PasswordEncoder passwordEncoder;

    @MockBean
    AESUtil aesUtil;

    @MockBean
    JwtUtil jwtUtil;

    IUserDto mockUser(String email) {
        return UserDto.builder()
                .userId(email)
                .build();
    }

    @Test
    public void testCreateUser_duplicate() {
        String email = "test@email.com";
        IUserDto param = UserDto.builder()
                .userId(email)
                .build();

        when(userRepository.getUserByEmail(email)).thenReturn(mockUser(email));

        UserException thrown = assertThrows(UserException.class, () -> {
            userService.createUser(param);
        });

        assertThat(thrown.getMessage()).isEqualTo("DUPLICATE_USERID");
        log.info(thrown.getMessage());
    }

    @Test
    public void testCreateUser_success() {
        String email = "test@email.com";
        IUserDto param = UserDto.builder()
                .userId(email)
                .build();

        when(userRepository.getUserByEmail(email)).thenReturn(null);
        when(passwordEncoder.encode(Mockito.anyString())).thenReturn(Mockito.anyString());

        userService.createUser(param);
    }

    @Test
    public void testLogin_no_user() {
        String email = "test@email.com";
        IUserDto param = UserDto.builder()
                .userId(email)
                .build();
        when(userRepository.getUserByEmail(email)).thenReturn(null);

        UnauthorizedException thrown = assertThrows(UnauthorizedException.class, () -> {
            userService.login(param);
        });

        assertThat(thrown.getMessage()).isEqualTo("CHECK_ID_OR_PASSWORD");
        log.info(thrown.getMessage());
    }

    @Test
    public void testLogin_password_not_match() {
        String email = "test@email.com";
        IUserDto param = UserDto.builder()
                .userId(email)
                .build();
        when(userRepository.getUserByEmail(email)).thenReturn(Mockito.any());
        when(passwordEncoder.matches(Mockito.anyString(), "encodedPassword"))
                .thenReturn(false);

        UnauthorizedException thrown = assertThrows(UnauthorizedException.class, () -> {
            userService.login(param);
        });

        assertThat(thrown.getMessage()).isEqualTo("CHECK_ID_OR_PASSWORD");
        log.info(thrown.getMessage());
    }

    @Test
    public void testLogin_success() {
        String email = "test@email.com";
        String password = "password";
        String encodedPassword = "encodedPassword";
        String token = "mockedToken";

        IUserDto user = UserDto.builder()
                .userId(email)
                .password(encodedPassword)
                .build();

        when(userRepository.getUserByEmail(email)).thenReturn(user);
        when(passwordEncoder.matches(password, encodedPassword)).thenReturn(true);

        when(jwtUtil.generateToken(user)).thenReturn(token);

        String resultToken = userService.login(UserDto.builder()
                .userId(email)
                .password(password)
                .build());

        assertThat(resultToken).isEqualTo(token);
        log.info(resultToken);
    }
}

