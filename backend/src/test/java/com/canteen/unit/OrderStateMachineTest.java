package com.canteen.unit;

import com.canteen.service.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 订单状态机静态规则测试。 */
class OrderStateMachineTest {

    @Test
    @DisplayName("主干闭环 10→20→30→40→50→60→70→80→90→100 全部合法")
    void happyPath() {
        int[] path = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        for (int i = 0; i < path.length - 1; i++) {
            assertTrue(OrderService.can(path[i], path[i + 1]),
                    path[i] + " -> " + path[i + 1] + " 应合法");
        }
    }

    @Test
    @DisplayName("任意状态可取消（99），且终态不可再流转")
    void cancelAndTerminal() {
        assertTrue(OrderService.can(10, 99));
        assertTrue(OrderService.can(20, 99));
        assertTrue(OrderService.can(40, 99));
        assertTrue(OrderService.can(60, 99));
        assertTrue(OrderService.next(100).isEmpty());
        assertTrue(OrderService.next(99).isEmpty());
        assertFalse(OrderService.can(99, 10));
        assertFalse(OrderService.can(100, 90));
    }

    @Test
    @DisplayName("禁止跳状态：10→70、30→90、10→100、60→80 均非法")
    void illegalJumps() {
        assertFalse(OrderService.can(10, 70));
        assertFalse(OrderService.can(30, 90));
        assertFalse(OrderService.can(10, 100));
        assertFalse(OrderService.can(60, 80));
        assertFalse(OrderService.can(80, 100));
    }

    @Test
    @DisplayName("禁止回退与未知状态；null 入参安全返回 false")
    void illegalBackwardsAndNull() {
        assertFalse(OrderService.can(90, 80));
        assertFalse(OrderService.can(70, 60));
        assertFalse(OrderService.can(999, 10));
        assertFalse(OrderService.can(10, 999));
        assertFalse(OrderService.can(null, 20));
        assertFalse(OrderService.can(10, null));
        assertTrue(OrderService.next(999).isEmpty());
    }

    @Test
    @DisplayName("30 待质检只能到 40 合格 / 41 不合格")
    void qualityBranch() {
        assertEquals(Set.of(40, 41), OrderService.next(30));
    }

    @Test
    @DisplayName("[修复验证] 41 不合格允许退回待质检复检或直接取消")
    void qualityRejectedCanReinspect() {
        assertEquals(Set.of(99, 30), OrderService.next(41));
        assertTrue(OrderService.can(41, 30));
        assertFalse(OrderService.can(41, 80), "复检前不能直接进入结算");
    }

    @Test
    @DisplayName("[期望行为] 41 不合格应允许复检")
    void shouldAllowReinspect() {
        assertTrue(OrderService.can(41, 30));
    }
}
