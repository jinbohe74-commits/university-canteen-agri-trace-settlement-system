package com.canteen.unit;

import com.canteen.common.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** JWT 签发/解析与密钥管理测试。 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-key-which-is-long-enough-1234567890";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expireMinutes", 120L);
        ReflectionTestUtils.setField(jwtUtil, "rememberDays", 7L);
    }

    @Test
    @DisplayName("签发后可解析回原始 claims")
    void createAndParse() {
        String token = jwtUtil.create(Map.of("userId", 42, "role", "FARMER"), false);
        Claims claims = jwtUtil.parse(token);
        assertEquals(42, claims.get("userId", Number.class).intValue());
        assertEquals("FARMER", claims.get("role", String.class));
        assertNotNull(claims.getExpiration());
    }

    @Test
    @DisplayName("记住我(7天)有效期明显长于普通登录(120分钟)")
    void rememberExpiration() {
        Claims normal = jwtUtil.parse(jwtUtil.create(Map.of("userId", 1), false));
        Claims remember = jwtUtil.parse(jwtUtil.create(Map.of("userId", 1), true));
        assertTrue(remember.getExpiration().after(normal.getExpiration()));
        long days = (remember.getExpiration().getTime() - System.currentTimeMillis()) / 86400000;
        assertTrue(days >= 6, "记住我应约 7 天");
    }

    @Test
    @DisplayName("篡改 payload 后签名校验失败")
    void tamperedTokenRejected() {
        String token = jwtUtil.create(Map.of("userId", 42), false);
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA" + "." + parts[2];
        assertThrows(JwtException.class, () -> jwtUtil.parse(tampered));
    }

    @Test
    @DisplayName("用另一把密钥签发的 token 无法解析")
    void foreignKeyRejected() {
        String foreign = Jwts.builder().claims(Map.of("userId", 1))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor("another-completely-different-secret-key-000000".getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertThrows(JwtException.class, () -> jwtUtil.parse(foreign));
    }

    @Test
    @DisplayName("过期 token 抛 ExpiredJwtException")
    void expiredTokenRejected() {
        ReflectionTestUtils.setField(jwtUtil, "expireMinutes", -1L);
        String token = jwtUtil.create(Map.of("userId", 1), false);
        assertThrows(io.jsonwebtoken.ExpiredJwtException.class, () -> jwtUtil.parse(token));
    }

    @Test
    @DisplayName("[修复验证] 已泄漏的硬编码 secret 被拒绝加载，离线伪造的 token 无法使用")
    void hardcodedSecretRejected() {
        // 原 backend/src/main/resources/application.yml:47 的明文密钥
        String leaked = "canteen-trace-direct-supply-trace-settlement-2026-secret-key";
        JwtUtil prod = new JwtUtil();
        ReflectionTestUtils.setField(prod, "secret", leaked);
        ReflectionTestUtils.setField(prod, "expireMinutes", 120L);
        ReflectionTestUtils.setField(prod, "rememberDays", 7L);

        String forged = Jwts.builder()
                .claims(Map.of("userId", 1, "role", "ADMIN"))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(Keys.hmacShaKeyFor(leaked.getBytes(StandardCharsets.UTF_8)))
                .compact();

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> prod.parse(forged));
        assertTrue(ex.getMessage().contains("已泄漏"), "泄漏密钥必须被列入黑名单");
    }

    @Test
    @DisplayName("[修复验证] 过短密钥被拒绝，避免弱签名")
    void shortSecretRejected() {
        JwtUtil weak = new JwtUtil();
        ReflectionTestUtils.setField(weak, "secret", "short");
        ReflectionTestUtils.setField(weak, "expireMinutes", 120L);
        ReflectionTestUtils.setField(weak, "rememberDays", 7L);
        assertThrows(IllegalStateException.class, () -> weak.create(Map.of("userId", 1), false));
    }
}
