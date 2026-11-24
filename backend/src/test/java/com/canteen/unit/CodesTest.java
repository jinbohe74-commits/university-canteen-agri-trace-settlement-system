package com.canteen.unit;

import com.canteen.common.Codes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 单号生成纯逻辑测试（修复后行为）。 */
class CodesTest {

    @Test
    @DisplayName("正常生成定宽单号")
    void nextNormal() {
        assertEquals("DD0001", Codes.next("DD", 4, 1, c -> false));
        assertEquals("JS101", Codes.next("JS", 3, 101, c -> false));
        assertEquals("PC0007", Codes.next("PC", 4, 7, c -> false));
    }

    @Test
    @DisplayName("自动跳过数据库中已存在的单号")
    void skipOccupiedCodes() {
        Set<String> used = Set.of("DD0001", "DD0002", "DD0004");
        assertEquals("DD0003", Codes.next("DD", 4, 1, used::contains));
    }

    @Test
    @DisplayName("start <= 0 时钳制为 1")
    void startBelowOne() {
        assertEquals("DD0001", Codes.next("DD", 4, 0, c -> false));
        assertEquals("DD0001", Codes.next("DD", 4, -99, c -> false));
    }

    @Test
    @DisplayName("流水号超过位数时按 10^width 回绕，不抛异常")
    void wrapAround() {
        assertEquals("PC001", Codes.next("PC", 3, 1000, "PC000"::equals));
        assertEquals("DD0000", Codes.next("DD", 4, 10000, c -> false));
    }

    @Test
    @DisplayName("[修复验证] exists 恒真时 guard 耗尽抛异常，绝不返回已被占用的单号")
    void shouldThrowWhenExhausted() {
        AtomicInteger probes = new AtomicInteger();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> Codes.next("X", 2, 1, c -> {
                    probes.incrementAndGet();
                    return true;
                }));
        assertEquals(50000, probes.get(), "guard 应恰好尝试 50000 次后放弃");
        assertTrue(ex.getMessage().contains("单号段已耗尽"));
    }

    @Test
    @DisplayName("[修复说明] 生成与插入非原子：并发相同单号由 DB 唯一键 + 全局异常(409)兜底")
    void concurrentCallersGetSameCode() {
        String a = Codes.next("DD", 4, 1, c -> false);
        String b = Codes.next("DD", 4, 1, c -> false);
        assertEquals(a, b, "纯内存生成不含锁，最终一致性依赖唯一约束冲突重试");
    }
}
