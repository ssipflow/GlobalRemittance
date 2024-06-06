package com.moin.globalRemittance.utils;

import com.moin.globalRemittance.dto.IUserDto;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtUtil {

    @Value("${variables.jwt.secret}")
    private String secret;

    @Value("${variables.jwt.expiration}")
    private long expirationTime;

    private SecretKeySpec secretKeySpec;

    @PostConstruct
    public void init() {
        byte[] secretKeyBytes = Base64.getDecoder().decode(secret);
        secretKeySpec = new SecretKeySpec(secretKeyBytes, SignatureAlgorithm.HS512.getJcaName());
    }

    public String generateToken(IUserDto user) {
        Instant now = Instant.now();
        Date issuedAt = Date.from(now);
        Date expiration = Date.from(now.plusMillis(expirationTime));

        final String id = Integer.toString(user.getId());

        return Jwts.builder()
                .setSubject(id)
                .claim("userId", user.getUserId())
                .claim("idType", user.getIdType())
                .setIssuedAt(issuedAt)
                .setExpiration(expiration)
                .signWith(secretKeySpec)
                .compact();
    }

    public Claims getClaimsFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(secretKeySpec)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
