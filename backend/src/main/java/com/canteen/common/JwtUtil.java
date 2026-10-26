package com.canteen.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * JWT 生成与解析
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${canteen.jwt.secret}")
    private String secret;

    @Value("${canteen.jwt.expire-minutes:120}")
    private long expireMinutes;

    @Value("${canteen.jwt.remember-days:7}")
    private long rememberDays;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String create(Map<String, Object> claims, boolean remember) {
        long seconds = remember ? rememberDays * 24 * 3600 : expireMinutes * 60;
        return Jwts.builder()
                .claims(claims)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + seconds * 1000))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
