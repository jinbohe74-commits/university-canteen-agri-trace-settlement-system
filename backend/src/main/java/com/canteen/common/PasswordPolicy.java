package com.canteen.common;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

/**
 * 全站唯一的密码策略：新增账号、管理员重置、用户自助改密都必须走这里。
 *
 * <p>曾经三处各写一份校验（UserService 建号、UserService 重置、前端表单），
 * 规则一改就漏一处。现在后端只有这一份，前端只做即时提示。</p>
 */
public final class PasswordPolicy {

    public static final int MIN_LEN = 8;
    public static final int MAX_LEN = 24;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 去掉易混淆的 I/l/0/O，管理员念给农户听时不会听错 */
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#";
    private static final Set<String> WEAK = Set.of(
            "12345678", "123456789", "1234567890", "password", "password1",
            "abcd1234", "qwerty123", "admin123", "abc12345", "11111111", "88888888");

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        if (password == null || password.length() < MIN_LEN
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new BizException(400, "密码至少 " + MIN_LEN + " 位，且须同时包含字母和数字");
        }
        if (password.length() > MAX_LEN) {
            throw new BizException(400, "密码最长 " + MAX_LEN + " 位");
        }
        if (WEAK.contains(password.toLowerCase(Locale.ROOT))) {
            throw new BizException(400, "该密码过于常见，请换一个（不要用 12345678、admin123 这类）");
        }
    }

    /** 生成 10 位随机初始密码，保证字母 + 数字各至少一个，可直接通过 {@link #validate} */
    public static String random() {
        for (int attempt = 0; attempt < 50; attempt++) {
            StringBuilder sb = new StringBuilder(10);
            for (int i = 0; i < 10; i++) {
                sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
            }
            String pwd = sb.toString();
            if (pwd.matches(".*[A-Za-z].*") && pwd.matches(".*\\d.*")) {
                return pwd;
            }
        }
        // 理论上到不了这里；兜底也强制含字母与数字
        StringBuilder sb = new StringBuilder("Ct7");
        for (int i = 0; i < 7; i++) {
            sb.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
