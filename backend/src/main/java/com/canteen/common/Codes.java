package com.canteen.common;

import java.util.function.Predicate;

/**
 * 业务单号生成：日期前缀 + 指定位数流水，自动跳过已存在编号。
 *
 * <p>修复 P1-9：guard 耗尽时不再返回「已占用」的脏号，改为快速失败并提示扩容/换号段；
 * 调用方须依赖数据库唯一键做最终并发兜底（插入冲突时重试）。</p>
 */
public final class Codes {

    private static final int MAX_ATTEMPTS = 50000;

    private Codes() {
    }

    public static String next(String prefix, int width, int start, Predicate<String> exists) {
        int seq = Math.max(1, start);
        int modulus = (int) Math.pow(10, width);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = prefix + String.format("%0" + width + "d", seq % modulus);
            seq++;
            if (!exists.test(code)) {
                return code;
            }
        }
        throw new IllegalStateException("单号段已耗尽，无法生成唯一单号：" + prefix);
    }
}
