package com.canteen.common;

import java.util.function.Predicate;

/**
 * 业务单号生成：日期前缀 + 指定位数流水，自动跳过已存在编号（防止并发/历史数据冲突）
 */
public final class Codes {

    private Codes() {
    }

    public static String next(String prefix, int width, int start, Predicate<String> exists) {
        int seq = Math.max(1, start);
        String code;
        int guard = 0;
        do {
            code = prefix + String.format("%0" + width + "d", seq % (int) Math.pow(10, width));
            seq++;
            guard++;
        } while (exists.test(code) && guard < 50000);
        return code;
    }
}
