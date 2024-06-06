package com.moin.globalRemittance.service;

import com.moin.globalRemittance.dto.IUserDto;
import com.moin.globalRemittance.exception.UnauthorizedException;
import com.moin.globalRemittance.repository.custom.UserRepository;
import com.moin.globalRemittance.utils.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;

    private final JwtUtil jwtUtil;


    public IUserDto authorization(String token) {
        if (token == null) {
            throw new UnauthorizedException("Invalid token");
        }
        Claims claims = jwtUtil.getClaimsFromToken(token);

        Integer id = Integer.parseInt(claims.getSubject());
        IUserDto user = userRepository.getUserById(id);
        if (user == null) {
            throw new UnauthorizedException("Invalid token");
        }

        Date expiration = claims.getExpiration();
        if (expiration.before(new Date())) {
            throw new UnauthorizedException("Token expired");
        }

        return user;
    }
}
