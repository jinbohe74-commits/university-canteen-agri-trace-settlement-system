package com.canteen.common;

/**
 * 模糊查询关键词清洗。
 *
 * <p>修复 P2：原实现把关键词拼进原生 inSql（仅剥离单引号），既存在 SQL 注入面，
 * 又允许 {@code %} / {@code _} 作为 LIKE 通配符绕过筛选（实测 {@code keyword=%25} 命中全表）。
 * 本工具剥离通配符并结合 {@code apply(..., {0})} 参数绑定，从根上消除这两类问题。</p>
 */
public final class SqlLike {

    private static final int MAX_LEN = 40;

    private SqlLike() {
    }

    /** 去掉 LIKE 通配符、反斜杠与控制字符，并限长。业务关键词本身不含这些字符。 */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_LEN; i++) {
            char c = raw.charAt(i);
            if (c == '%' || c == '_' || c == '\\' || Character.isISOControl(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /**
     * 可直接绑定到 {@code LIKE {0}} 的 {@code %关键词%}，参数由 MyBatis 预编译。
     * 清洗后为空（例如用户只输入 {@code %}）时返回 {@code null}，调用方应改为返回空结果集，
     * 避免退化成 {@code LIKE '%%'} 再次命中全表。
     */
    public static String contains(String raw) {
        String c = clean(raw);
        return c.isEmpty() ? null : "%" + c + "%";
    }
}
