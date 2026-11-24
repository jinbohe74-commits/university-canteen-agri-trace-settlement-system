package com.canteen.unit;

import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.entity.Orders;
import com.canteen.entity.SysNotification;
import com.canteen.mapper.OrdersMapper;
import com.canteen.mapper.SysNotificationMapper;
import com.canteen.mapper.SysUserMapper;
import com.canteen.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 定向通知的解析契约：只发给订单相关方，且同一账号一次事件只收一条。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceUnitTest {

    @Mock private SysNotificationMapper notificationMapper;
    @Mock private OrdersMapper ordersMapper;
    @Mock private SysUserMapper userMapper;
    @InjectMocks private NotificationService service;

    @BeforeEach
    void setUp() {
        LoginUser me = new LoginUser();
        me.setUserId(999L);
        me.setRealName("触发人");
        me.setRole("INSPECTOR");
        UserContext.set(me);

        Orders o = new Orders();
        o.setId(100L);
        o.setOrderNo("DD202611110001");
        o.setFarmerId(2L);
        o.setCanteenId(1L);
        when(ordersMapper.selectById(100L)).thenReturn(o);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("[2026-11-11] 同一账号被 CANTEEN 和 PURCHASER 双重解析时只落一条通知")
    void noDuplicateWhenPartyOverlaps() {
        when(notificationMapper.userIdsOfCanteen(1L)).thenReturn(List.of(10L));
        when(notificationMapper.userIdsOfFarmer(2L)).thenReturn(List.of(3L));

        service.toOrderParties(100L, "DEPART", "DD202611110001", "批次已发车", "正文",
                Map.of("CANTEEN", "/purchaser/orders", "PURCHASER", "/purchaser/orders",
                        "FARMER", "/farmer/orders"));

        ArgumentCaptor<SysNotification> cap = ArgumentCaptor.forClass(SysNotification.class);
        verify(notificationMapper, times(2)).insert(cap.capture());
        List<Long> ids = cap.getAllValues().stream().map(SysNotification::getRecipientId).toList();
        assertEquals(2, ids.stream().distinct().count(), "接收人不应重复，实际：" + ids);
        assertTrue(ids.contains(10L) && ids.contains(3L));
    }

    @Test
    @DisplayName("[2026-11-11] 采购方只解析订单所属食堂的绑定账号，不群发给全部采购员")
    void purchaserResolvedByOrderCanteen() {
        when(notificationMapper.userIdsOfCanteen(1L)).thenReturn(List.of(10L));

        service.toOrderParties(100L, "QUALITY", "DD202611110001", "质检合格", "正文",
                Map.of("CANTEEN", "/purchaser/orders"));

        ArgumentCaptor<SysNotification> cap = ArgumentCaptor.forClass(SysNotification.class);
        verify(notificationMapper).insert(cap.capture());
        assertEquals(10L, cap.getValue().getRecipientId());
        verify(notificationMapper, never()).userIdsOfRole("PURCHASER");
    }

    @Test
    @DisplayName("[2026-11-11] 质检通知优先发给实际检测该批次的质检员")
    void inspectorResolvedByQualityReport() {
        when(notificationMapper.userIdsOfOrderInspector(100L)).thenReturn(List.of(15L));

        service.toOrderParties(100L, "SHIP", "DD202611110001", "新批次待质检", "正文",
                Map.of("INSPECTOR", "/inspector/tasks"));

        ArgumentCaptor<SysNotification> cap = ArgumentCaptor.forClass(SysNotification.class);
        verify(notificationMapper).insert(cap.capture());
        assertEquals(15L, cap.getValue().getRecipientId());
        assertEquals("/inspector/tasks", cap.getValue().getRoute());
        verify(notificationMapper, never()).userIdsOfRole("INSPECTOR");
    }

    @Test
    @DisplayName("[2026-11-11] 不给触发操作的人自己发通知")
    void notSentToOperatorItself() {
        when(notificationMapper.userIdsOfCanteen(1L)).thenReturn(List.of(999L, 10L));

        service.toOrderParties(100L, "QUALITY", "DD202611110001", "质检合格", "正文",
                Map.of("CANTEEN", "/purchaser/orders"));

        ArgumentCaptor<SysNotification> cap = ArgumentCaptor.forClass(SysNotification.class);
        verify(notificationMapper).insert(cap.capture());
        assertEquals(10L, cap.getValue().getRecipientId());
    }
}
