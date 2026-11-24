package com.canteen.unit;

import com.canteen.entity.CreditScore;
import com.canteen.entity.Farmer;
import com.canteen.mapper.CreditScoreMapper;
import com.canteen.mapper.FarmerMapper;
import com.canteen.service.ConfigService;
import com.canteen.service.CreditService;
import com.canteen.service.LogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 信用分边界、幂等性测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreditServiceTest {

    @Mock private FarmerMapper farmerMapper;
    @Mock private CreditScoreMapper creditMapper;
    @Mock private ConfigService configService;
    @Mock private LogService logService;
    @InjectMocks private CreditService creditService;

    private void farmerWithScore(BigDecimal score) {
        Farmer f = new Farmer();
        f.setId(1L);
        f.setName("丰禾农业合作社");
        f.setCreditScore(score);
        when(farmerMapper.selectById(1L)).thenReturn(f);
    }

    @Test
    @DisplayName("信用分变动必须写操作日志（管理员要能追溯是谁改的）")
    void writesOperationLog() {
        farmerWithScore(new BigDecimal("90"));
        creditService.change(1L, 330L, BigDecimal.valueOf(-2), "生鲜损耗率超过阈值");
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(logService).record(eq("信用评价"), content.capture());
        String c = content.getValue();
        assertTrue(c.contains("丰禾农业合作社"), "日志里要有农户名，否则按姓名搜不到: " + c);
        assertTrue(c.contains("90") && c.contains("88"), "日志里要有变动前后分值: " + c);
        assertTrue(c.contains("-2"), "日志里要有变动值: " + c);
        assertTrue(c.contains("生鲜损耗率超过阈值"), "日志里要有原因: " + c);
        assertTrue(c.contains("330"), "日志里要有关联订单: " + c);
    }

    @Test
    @DisplayName("加分不超过 100，扣分不低于 0")
    void clampBounds() {
        farmerWithScore(new BigDecimal("99"));
        creditService.change(1L, 10L, BigDecimal.valueOf(5), "test");
        ArgumentCaptor<CreditScore> cap = ArgumentCaptor.forClass(CreditScore.class);
        verify(creditMapper).insert(cap.capture());
        assertEquals(0, new BigDecimal("100").compareTo(cap.getValue().getAfterScore()));

        clearInvocations(creditMapper);
        farmerWithScore(BigDecimal.ONE);
        creditService.change(1L, 10L, BigDecimal.valueOf(-5), "test");
        ArgumentCaptor<CreditScore> cap2 = ArgumentCaptor.forClass(CreditScore.class);
        verify(creditMapper).insert(cap2.capture());
        assertEquals(0, BigDecimal.ZERO.compareTo(cap2.getValue().getAfterScore()));
    }

    @Test
    @DisplayName("信用分为 null 时按初始 80 分计算")
    void nullDefaultsTo80() {
        farmerWithScore(null);
        creditService.change(1L, 10L, BigDecimal.ONE, "test");
        ArgumentCaptor<CreditScore> cap = ArgumentCaptor.forClass(CreditScore.class);
        verify(creditMapper).insert(cap.capture());
        assertEquals(0, new BigDecimal("80").compareTo(cap.getValue().getBeforeScore()));
        assertEquals(0, new BigDecimal("81").compareTo(cap.getValue().getAfterScore()));
    }

    @Test
    @DisplayName("农户不存在时安全返回，不写明细")
    void missingFarmerIsNoop() {
        when(farmerMapper.selectById(1L)).thenReturn(null);
        assertDoesNotThrow(() -> creditService.change(1L, 10L, BigDecimal.ONE, "test"));
        verify(creditMapper, never()).insert(any(CreditScore.class));
    }

    @Test
    @DisplayName("[修复验证] 同一订单重复调用只记一次分（幂等）")
    void idempotent() {
        farmerWithScore(new BigDecimal("80"));
        // 第二次调用时库中已存在同订单同分值同原因的明细
        when(creditMapper.selectCount(any())).thenReturn(0L, 1L);
        creditService.change(1L, 10L, BigDecimal.valueOf(-2), "损耗扣分");
        creditService.change(1L, 10L, BigDecimal.valueOf(-2), "损耗扣分");
        verify(creditMapper, times(1)).insert(any(CreditScore.class));
        verify(farmerMapper, times(1)).updateById(any(Farmer.class));
    }

    @Test
    @DisplayName("[修复验证] change 为 null 时安全返回，不写明细")
    void nullChangeIsNoop() {
        farmerWithScore(new BigDecimal("80"));
        assertDoesNotThrow(() -> creditService.change(1L, 10L, null, "test"));
        verify(creditMapper, never()).insert(any(CreditScore.class));
    }

    @Test
    @DisplayName("规则方法从配置读取加减分值")
    void rulesReadConfig() {
        farmerWithScore(new BigDecimal("80"));
        when(configService.getDouble("credit_punish_loss", -2)).thenReturn(-3.0);
        creditService.punishLoss(1L, 10L);
        ArgumentCaptor<CreditScore> cap = ArgumentCaptor.forClass(CreditScore.class);
        verify(creditMapper).insert(cap.capture());
        assertEquals(0, new BigDecimal("77").compareTo(cap.getValue().getAfterScore()));
    }
}
