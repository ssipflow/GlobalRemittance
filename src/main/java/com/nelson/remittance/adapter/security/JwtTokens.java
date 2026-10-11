package com.nelson.remittance.adapter.security;

import static com.nelson.remittance.domain.BusinessException.Code.UNAUTHORIZED;

import com.nelson.remittance.application.Ports;
import com.nelson.remittance.application.TransferUseCases;
import com.nelson.remittance.domain.BusinessException;
import com.nelson.remittance.domain.User;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Date;

public final class JwtTokens implements Ports.Tokens {
    private final Key key;
    private final Clock clock;

    public JwtTokens(String base64Secret, Clock clock) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Secret);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("REMITTANCE_JWT_SECRET must be Base64.");
        }
        if (bytes.length < 32) {
            throw new IllegalArgumentException(
                    "REMITTANCE_JWT_SECRET must contain at least 32 bytes.");
        }
        key = Keys.hmacShaKeyFor(bytes);
        this.clock = clock;
    }

    @Override
    public String issue(User user) {
        var now = clock.instant();
        // JWT와 견적이 같은 주입 Clock을 사용하여 정확한 30분 경계를 검증할 수 있다.
        var expiry = now.plusSeconds(1800);
        return Jwts.builder()
                .setIssuer("global-remittance")
                .setSubject(Long.toString(user.id()))
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expiry))
                .claim("id", user.id())
                .claim("userId", user.userId())
                .claim("name", user.name())
                .claim(
                        "expireTime",
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                                expiry.atZone(TransferUseCases.KST)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    @Override
    public long verify(String token) {
        try {
            var parsed =
                    Jwts.parserBuilder()
                            .setSigningKey(key)
                            .requireIssuer("global-remittance")
                            .setClock(() -> Date.from(clock.instant()))
                            .build()
                            .parseClaimsJws(token);
            var claims = parsed.getBody();
            long id = Long.parseLong(claims.getSubject());
            Object claimId = claims.get("id");
            if (!"HS256".equals(parsed.getHeader().getAlgorithm())
                    || id <= 0
                    || id > Integer.MAX_VALUE
                    || !(claimId instanceof Number)
                    || ((Number) claimId).longValue() != id
                    || claims.getExpiration() == null
                    || !clock.instant().isBefore(claims.getExpiration().toInstant())
                    || !(claims.get("userId") instanceof String)
                    || !(claims.get("name") instanceof String)
                    || !(claims.get("expireTime") instanceof String)) {
                throw new BusinessException(UNAUTHORIZED);
            }
            return id;
        } catch (JwtException | IllegalArgumentException error) {
            throw new BusinessException(UNAUTHORIZED);
        }
    }
}
