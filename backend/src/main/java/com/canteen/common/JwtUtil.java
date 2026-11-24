package com.canteen.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 生成与解析。
 *
 * <p>安全约定（修复 P0-2）：密钥不得写在配置文件里。优先级为
 * ①环境变量 CANTEEN_JWT_SECRET（生产推荐）②本地文件 config/jwt.secret
 * ③首次启动自动生成随机密钥并落盘。历史泄漏密钥一律拒绝启动。</p>
 */
@Slf4j
@Component
public class JwtUtil {

    /** 曾经明文写入 application.yml 的泄漏密钥，必须永久禁用 */
    private static final String LEAKED_SECRET = "canteen-trace-direct-supply-trace-settlement-2026-secret-key";
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${canteen.jwt.secret:}")
    private String secret;

    @Value("${canteen.jwt.secret-file:./config/jwt.secret}")
    private String secretFile;

    @Value("${canteen.jwt.expire-minutes:120}")
    private long expireMinutes;

    @Value("${canteen.jwt.remember-days:7}")
    private long rememberDays;

    private volatile SecretKey key;

    private SecretKey key() {
        SecretKey k = key;
        if (k == null) {
            synchronized (this) {
                k = key;
                if (k == null) {
                    k = Keys.hmacShaKeyFor(resolveSecret().getBytes(StandardCharsets.UTF_8));
                    key = k;
                }
            }
        }
        return k;
    }

    private String resolveSecret() {
        String configured = secret == null ? "" : secret.trim();
        if (!configured.isEmpty()) {
            if (LEAKED_SECRET.equals(configured)) {
                throw new IllegalStateException("检测到已泄漏的 JWT 密钥，拒绝启动；请设置环境变量 CANTEEN_JWT_SECRET");
            }
            if (configured.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException("JWT 密钥长度不足 32 字节，请设置环境变量 CANTEEN_JWT_SECRET");
            }
            return configured;
        }
        Path path = Paths.get(secretFile == null ? "./config/jwt.secret" : secretFile);
        try {
            if (Files.exists(path)) {
                String stored = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!LEAKED_SECRET.equals(stored) && stored.length() >= MIN_SECRET_LENGTH) {
                    return stored;
                }
                log.warn("密钥文件内容不合规，将重新生成");
            }
            byte[] random = new byte[48];
            new SecureRandom().nextBytes(random);
            String generated = Base64.getEncoder().encodeToString(random);
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, generated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("已生成随机 JWT 密钥文件 {}（请加入 .gitignore，勿提交到仓库）", path.toAbsolutePath());
            return generated;
        } catch (IOException e) {
            throw new IllegalStateException("无法读写 JWT 密钥文件: " + path.toAbsolutePath(), e);
        }
    }

    /** 签发 token，附带唯一 jti 供注销时精确吊销。 */
    public String create(Map<String, Object> claims, boolean remember) {
        return create(claims, remember, System.currentTimeMillis());
    }

    /**
     * 指定签发时间签发 token。
     *
     * <p>改密场景：先按 cutoff 吊销旧 token，再用 cutoff+1 签发新 token，
     * 否则同一毫秒内签发的新令牌会被自己的截止线判定为已吊销。</p>
     */
    public String create(Map<String, Object> claims, boolean remember, long issuedAtMillis) {
        long now = issuedAtMillis;
        long seconds = remember ? rememberDays * 24 * 3600 : expireMinutes * 60;
        Map<String, Object> all = new java.util.HashMap<>(claims);
        // JWT 标准 iat 仅秒级精度，额外携带毫秒时间戳，避免改密后同秒签发的新 token 被截止线误杀
        all.put("iatMs", now);
        return Jwts.builder()
                .claims(all)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + seconds * 1000))
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
