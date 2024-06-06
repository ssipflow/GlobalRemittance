package com.moin.globalRemittance.service;

import com.moin.globalRemittance.dto.IUserDto;
import com.moin.globalRemittance.exception.UnauthorizedException;
import com.moin.globalRemittance.exception.UserException;
import com.moin.globalRemittance.repository.custom.UserRepository;
import com.moin.globalRemittance.service.model.UserDto;
import com.moin.globalRemittance.utils.AESUtil;
import com.moin.globalRemittance.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;
    private final AESUtil aesUtil;
    private final JwtUtil jwtUtil;

    public void createUser(IUserDto dto) {
        IUserDto user = userRepository.getUserByEmail(dto.getUserId());
        if (user != null) {
            throw new UserException("DUPLICATE_USERID");
        }

        String encryptedPassword = passwordEncoder.encode(dto.getPassword());
        String encryptedIdValue;
        try {
            encryptedIdValue = aesUtil.encrypt(dto.getIdValue());
        } catch(Exception e) {
            logger.error(e.getMessage());
            throw new RuntimeException("ID_VALUE_ENCRYPT_ERROR");
        }

        user = UserDto.builder()
                .userId(dto.getUserId())
                .password(encryptedPassword)
                .name(dto.getName())
                .idType(dto.getIdType())
                .idValue(encryptedIdValue)
                .build();

        userRepository.createUser(user);
    }

    public String login(IUserDto dto) {
        IUserDto user = userRepository.getUserByEmail(dto.getUserId());
        if (user == null) {
            throw new UnauthorizedException("CHECK_ID_OR_PASSWORD");
        }

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new UnauthorizedException("Check id or password");
        }

        return jwtUtil.generateToken(user);
    }
}
