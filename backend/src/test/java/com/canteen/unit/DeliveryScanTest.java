package com.canteen.unit;

import com.canteen.service.DeliveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扫码交付的批次匹配规则。
 *
 * <p>回归点：改造前只认「末 6 位完全相等」，操作员照着页面上的批次清单
 * 抄了别的车的尾号、或把整串条码粘进来，都会判错——现场看着就是「明明输对了」。</p>
 */
class DeliveryScanTest {

    private static final String BATCH = "PC20261110F00020047599";

    @Test
    @DisplayName("整串批次号扫码应通过")
    void fullCodePasses() {
        assertTrue(DeliveryService.matchesBatch(BATCH, BATCH));
        assertTrue(DeliveryService.matchesBatch(BATCH, "pc20261110f00020047599"));
    }

    @Test
    @DisplayName("末 6 位尾号应通过")
    void tailPasses() {
        assertTrue(DeliveryService.matchesBatch(BATCH, "047599"));
    }

    @Test
    @DisplayName("扫码结果带空格/横杠也应通过")
    void separatorsIgnored() {
        assertTrue(DeliveryService.matchesBatch(BATCH, " 047 599 "));
        assertTrue(DeliveryService.matchesBatch(BATCH, "PC20261110-F00020047599"));
    }

    @Test
    @DisplayName("其他批次的尾号必须被拒")
    void otherBatchRejected() {
        // 这正是现场踩到的坑：另一辆车 PS20261111423 的批次尾号
        assertFalse(DeliveryService.matchesBatch(BATCH, "043455"));
    }

    @Test
    @DisplayName("空值与过短输入必须被拒")
    void blankRejected() {
        assertFalse(DeliveryService.matchesBatch(BATCH, ""));
        assertFalse(DeliveryService.matchesBatch(BATCH, null));
        assertFalse(DeliveryService.matchesBatch(BATCH, "0475"));
        assertFalse(DeliveryService.matchesBatch(null, "047599"));
    }
}
