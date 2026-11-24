package com.canteen.unit;

import com.canteen.common.BizException;
import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.dto.InboundDTO;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 入库结算金额计算（真实 InboundService.confirm 逻辑，Mapper 全 mock）——修复后行为。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InboundServiceUnitTest {

    @Mock private OrdersMapper ordersMapper;
    @Mock private InboundRecordMapper inboundMapper;
    @Mock private OrderItemMapper itemMapper;
    @Mock private SettlementMapper settlementMapper;
    @Mock private SettlementItemMapper settlementItemMapper;
    @Mock private QualityReportMapper qualityMapper;
    @Mock private ProductMapper productMapper;
    @Mock private FarmerMapper farmerMapper;
    @Mock private CanteenMapper canteenMapper;
    @Mock private RefService refService;
    @Mock private OrderService orderService;
    @Mock private CreditService creditService;
    @Mock private ConfigService configService;
    @Mock private TraceService traceService;
    @Mock private LogService logService;
    /** 入库即入库存（第三段新增依赖），下面 fullReceive 会断言 addStock 被调用一次 */
    @Mock private StockService stockService;
    /** 第五段：入库后按订单归属发定向通知。mock 掉以免单测依赖数据库/登录上下文 */
    @Mock private NotificationService notificationService;
    @InjectMocks private InboundService inboundService;

    @BeforeEach
    void setUp() {
        LoginUser lu = new LoginUser();
        lu.setUserId(1L);
        lu.setRealName("测试采购员");
        lu.setRole("PURCHASER");
        lu.setCanteenId(1L);
        UserContext.set(lu);
        when(configService.getDouble(eq("warn_loss_rate"), anyDouble())).thenReturn(5.0);
        when(configService.getDouble(eq("quality_deduction_rate"), anyDouble())).thenReturn(0.2);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private Orders order(int status, String qty, String price) {
        Orders o = new Orders();
        o.setId(100L);
        o.setOrderNo("DD202611090001");
        o.setStatus(status);
        o.setQuantity(new BigDecimal(qty));
        o.setUnitPrice(new BigDecimal(price));
        o.setTotalAmount(new BigDecimal(qty).multiply(new BigDecimal(price)));
        o.setVersion(1);
        o.setFarmerId(2L);
        o.setCanteenId(1L);
        o.setProductId(3L);
        when(ordersMapper.selectById(any())).thenReturn(o);
        return o;
    }

    private InboundDTO dto(String receive) {
        InboundDTO d = new InboundDTO();
        d.setOrderId(100L);
        d.setReceiveQuantity(new BigDecimal(receive));
        // 2026-11-10 起：有损耗必须说明原因（写入结算单，供对账追责）。
        // 默认带上，避免每个「有损耗」的用例都要重复写一遍。
        d.setRemark("运输途中压伤，已现场拍照并与农户确认扣减");
        return d;
    }

    private static void assertMoney(String expected, Object actual) {
        assertNotNull(actual, "金额不应为 null");
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual),
                "期望 " + expected + "，实际 " + actual);
    }

    @Test
    @DisplayName("README 示例：400kg / 实收 380kg / ¥1.80 → 720 / 36 / 684")
    void readmeExample() {
        order(60, "400", "1.80");
        Map<String, Object> res = inboundService.confirm(dto("380"));

        assertMoney("720.00", res.get("orderAmount"));
        assertMoney("36.00", res.get("lossDeduction"));
        assertMoney("684.00", res.get("actualAmount"));
        assertMoney("20.00", res.get("lossQuantity"));
        assertMoney("5.00", res.get("lossRate"));

        ArgumentCaptor<Settlement> cap = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper).insert(cap.capture());
        Settlement st = cap.getValue();
        assertMoney("720.00", st.getTotalAmount());
        assertMoney("36.00", st.getLossDeduction());
        assertMoney("684.00", st.getActualAmount());
        assertEquals("待审核", st.getStatus());
    }

    @Test
    @DisplayName("全额收货无损耗，实付等于订单金额")
    void fullReceive() {
        order(60, "400", "1.80");
        Map<String, Object> res = inboundService.confirm(dto("400"));
        assertMoney("0.00", res.get("lossDeduction"));
        assertMoney("720.00", res.get("actualAmount"));
        verify(creditService, never()).punishLoss(anyLong(), anyLong());
    }

    @Test
    @DisplayName("入库即入库存：按实收量（不是下单量）加库存")
    void inboundAddsStock() {
        order(60, "400", "1.80");
        inboundService.confirm(dto("380"));

        ArgumentCaptor<BigDecimal> qty = ArgumentCaptor.forClass(BigDecimal.class);
        verify(stockService).addStock(eq(1L), eq(3L), qty.capture());
        assertMoney("380", qty.getValue());
    }

    @Test
    @DisplayName("金额按 HALF_UP 保留 2 位小数")
    void rounding() {
        order(60, "3.333", "1.005");
        Map<String, Object> res = inboundService.confirm(dto("3.333"));
        assertMoney("3.35", res.get("orderAmount"));
        assertMoney("3.35", res.get("actualAmount"));
    }

    @Test
    @DisplayName("实收为 0 / 负数 / 大于下单量均被拒绝")
    void invalidReceiveQuantity() {
        order(60, "400", "1.80");
        assertThrows(BizException.class, () -> inboundService.confirm(dto("0")));
        assertThrows(BizException.class, () -> inboundService.confirm(dto("-1")));
        assertThrows(BizException.class, () -> inboundService.confirm(dto("401")));
    }

    @Test
    @DisplayName("非【已送达(60)】订单禁止入库")
    void wrongStatusRejected() {
        order(50, "400", "1.80");
        assertThrows(BizException.class, () -> inboundService.confirm(dto("380")));
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test
    @DisplayName("[修复验证] 负/零单价被拒绝，不生成负金额结算单")
    void negativeUnitPriceRejected() {
        order(60, "400", "-1.80");
        BizException ex = assertThrows(BizException.class, () -> inboundService.confirm(dto("380")));
        assertTrue(ex.getMessage().contains("单价"));
        verify(settlementMapper, never()).insert(any(Settlement.class));
    }

    @Test
    @DisplayName("[2026-11-10] 质检扣款只认库内报告：DTO 已不含 qualityResult，前端无从改判")
    void clientQualityInputIgnored() {
        order(60, "400", "1.80");
        InboundDTO d = dto("380");
        // DTO 不再提供 qualityResult 字段（构造期就无法传入错误结论）；
        // 库中无质检报告 → 不扣款，证明判定完全来自报告。
        Map<String, Object> res = inboundService.confirm(d);
        assertMoney("684.00", res.get("actualAmount"));
        ArgumentCaptor<Settlement> cap = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper).insert(cap.capture());
        assertMoney("0.00", cap.getValue().getDeduction());
        verify(qualityMapper, atLeastOnce()).selectOne(any());
    }

    @Test
    @DisplayName("[2026-11-10] 有损耗但没写原因 → 拒绝入库")
    void lossRequiresReason() {
        order(60, "400", "1.80");
        InboundDTO noReason = dto("380");
        noReason.setRemark(null);
        BizException e = assertThrows(BizException.class, () -> inboundService.confirm(noReason));
        assertTrue(e.getMessage().contains("损耗原因"), "应提示补充损耗原因，实际：" + e.getMessage());
        verify(settlementMapper, never()).insert(any(Settlement.class));

        InboundDTO tooShort = dto("380");
        tooShort.setRemark("压伤");
        assertThrows(BizException.class, () -> inboundService.confirm(tooShort));
        verify(settlementMapper, never()).insert(any(Settlement.class));

        // 补上原因即可正常入库
        Map<String, Object> res = inboundService.confirm(dto("380"));
        assertMoney("684.00", res.get("actualAmount"));
    }

    @Test
    @DisplayName("[2026-11-10] 无损耗时不需要填原因")
    void noLossNoReasonNeeded() {
        order(60, "400", "1.80");
        InboundDTO d = dto("400");
        d.setRemark(null);
        Map<String, Object> res = inboundService.confirm(d);
        assertMoney("720.00", res.get("actualAmount"));
    }

    @Test
    @DisplayName("[修复验证] 质检扣款由质检报告驱动，比例来自系统参数")
    void qualityDeductionFromReport() {
        order(60, "400", "1.80");
        QualityReport qr = new QualityReport();
        qr.setResult("不合格");
        qr.setInspectorId(5L);
        when(qualityMapper.selectOne(any())).thenReturn(qr);
        when(configService.getDouble(eq("quality_deduction_rate"), anyDouble())).thenReturn(0.1);

        Map<String, Object> res = inboundService.confirm(dto("380"));
        assertMoney("612.00", res.get("actualAmount"));   // 720 - 36 - 72
        ArgumentCaptor<Settlement> cap = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementMapper).insert(cap.capture());
        assertMoney("72.00", cap.getValue().getDeduction());
    }

    @Test
    @DisplayName("[修复验证] 入库记录质检员 ID 取自质检报告，不再硬编码 14")
    void inspectorIdFromQualityReport() {
        order(60, "400", "1.80");
        QualityReport qr = new QualityReport();
        qr.setResult("合格");
        qr.setInspectorId(77L);
        when(qualityMapper.selectOne(any())).thenReturn(qr);

        inboundService.confirm(dto("380"));
        ArgumentCaptor<InboundRecord> cap = ArgumentCaptor.forClass(InboundRecord.class);
        verify(inboundMapper).insert(cap.capture());
        assertEquals(77L, cap.getValue().getInspectorId());
    }

    @Test
    @DisplayName("[修复验证] 无质检报告时质检员 ID 留空而非写死")
    void inspectorIdNullWithoutReport() {
        order(60, "400", "1.80");
        inboundService.confirm(dto("380"));
        ArgumentCaptor<InboundRecord> cap = ArgumentCaptor.forClass(InboundRecord.class);
        verify(inboundMapper).insert(cap.capture());
        assertNull(cap.getValue().getInspectorId());
    }

    @Test
    @DisplayName("[修复验证] 损耗率「等于」阈值不扣信用分（README 口径为超阈值）")
    void thresholdBoundary() {
        order(60, "400", "1.80"); // 实收 380 → 损耗率恰为 5.00%
        Map<String, Object> res = inboundService.confirm(dto("380"));
        assertEquals(Boolean.FALSE, res.get("lossAlarm"));
        verify(creditService, never()).punishLoss(anyLong(), anyLong());
    }

    @Test
    @DisplayName("[2026-11-11] 入库后只通知订单相关方（农户 + 审核管理员），不广播给全部角色")
    void inboundNotificationIsTargeted() {
        order(60, "400", "1.80");
        inboundService.confirm(dto("380"));
        verify(notificationService).toOrderParties(
                eq(100L), eq("INBOUND"), eq("DD202611090001"), anyString(), anyString(),
                ArgumentMatchers.<Map<String, String>>argThat(routes ->
                        routes.size() == 2
                                && "/farmer/settlement".equals(routes.get("FARMER"))
                                && "/admin/settlements".equals(routes.get("ADMIN"))
                                && !routes.containsKey("PURCHASER")
                                && !routes.containsKey("INSPECTOR")));
    }
}
