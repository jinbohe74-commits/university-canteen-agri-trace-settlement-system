package com.canteen.common;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JWT 吊销记录。
 *
 * <p>解决「注销/改密/停用后旧 token 仍可用」：logout 精确吊销 jti，
 * 改密/停用按用户设置时间截止线（该时间点之前签发的 token 全部失效）。
 * 记录以追加行方式落到本地文件，重启后依然有效；过期记录会在下次加载时丢弃。</p>
 *
 * <p>生产多实例部署时应把这里换成 Redis / 数据库共享存储，接口保持不变。</p>
 */
@Slf4j
@Component
public class TokenRevocationStore {

    private final ConcurrentHashMap<String, Long> revokedJti = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> userCutoff = new ConcurrentHashMap<>();
    private final Path file;

    public TokenRevocationStore(
            @Value("${canteen.jwt.revocation-file:./config/token-revocations.txt}") String path) {
        this.file = Paths.get(path);
    }

    @PostConstruct
    void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            long now = System.currentTimeMillis();
            int kept = 0;
            for (String line : lines) {
                String[] p = line.split(":", 3);
                if (p.length != 3) {
                    continue;
                }
                try {
                    if ("J".equals(p[0])) {
                        long exp = Long.parseLong(p[2]);
                        if (exp > now) {
                            revokedJti.put(p[1], exp);
                            kept++;
                        }
                    } else if ("U".equals(p[0])) {
                        long cut = Long.parseLong(p[2]);
                        userCutoff.merge(Long.parseLong(p[1]), cut, Math::max);
                        kept++;
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略损坏行
                }
            }
            log.info("已加载 {} 条未过期的 token 吊销记录", kept);
        } catch (IOException e) {
            log.warn("token 吊销记录读取失败，本次启动按空记录处理: {}", e.getMessage());
        }
    }

    /** 精确吊销单个 token（注销）。 */
    public synchronized void revokeJti(String jti, long expireAtMillis) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        revokedJti.put(jti, expireAtMillis);
        append("J:" + jti + ":" + expireAtMillis);
    }

    /** 吊销某用户在 cutoff 之前签发的所有 token（改密、停用、删除）。 */
    public synchronized void revokeUser(long userId) {
        revokeUserBefore(userId, System.currentTimeMillis());
    }

    /**
     * 指定截止线吊销。
     *
     * <p>改密后需要"旧 token 全失效、本次改密后新签发的 token 仍可用"，
     * 调用方取 cutoff，再用 cutoff+1 签发新 token 即可。</p>
     */
    public synchronized void revokeUserBefore(long userId, long cutoffMillis) {
        userCutoff.merge(userId, cutoffMillis, Math::max);
        append("U:" + userId + ":" + cutoffMillis);
    }

    /**
     * @param jti          token 唯一标识，缺失视为非法（修复后签发的 token 必带 jti）
     * @param issuedAtMillis 签发时间（毫秒）
     */
    public boolean isRevoked(long userId, String jti, long issuedAtMillis) {
        if (jti == null || jti.isBlank()) {
            return true;
        }
        Long cutoff = userCutoff.get(userId);
        if (cutoff != null && issuedAtMillis <= cutoff) {
            return true;
        }
        return revokedJti.containsKey(jti);
    }

    /** 定期清理内存中已过期的 jti，避免长期运行内存增长。 */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        revokedJti.entrySet().removeIf(e -> e.getValue() <= now);
    }

    private void append(String line) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // 落盘失败不能让注销接口报错，但必须留痕；重启后该条会失效
            log.error("token 吊销记录写入失败: {}", e.getMessage());
        }
    }
}
