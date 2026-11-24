package com.canteen.unit;

import com.canteen.common.BizException;
import com.canteen.entity.SysConfig;
import com.canteen.mapper.SysConfigMapper;
import com.canteen.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 系统参数解析与五因子权重测试（修复后行为）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigServiceTest {

    @Mock
    private SysConfigMapper configMapper;
    @InjectMocks
    private ConfigService configService;

    private static SysConfig cfg(String value) {
        SysConfig c = new SysConfig();
        c.setConfigKey("k");
        c.setConfigValue(value);
        return c;
    }

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(configMapper);
    }

    @Test
    @DisplayName("getDouble 非法值静默回退默认值")
    void getDoubleFallback() {
        when(configMapper.selectOne(any())).thenReturn(cfg("not-a-number"));
        assertEquals(0.2, configService.getDouble("k", 0.2), 1e-9);
    }

    @Test
    @DisplayName("配置缺失时返回默认值")
    void getDefaultWhenMissing() {
        when(configMapper.selectOne(any())).thenReturn(null);
        assertEquals("fallback", configService.get("missing", "fallback"));
        assertEquals(7, configService.getInt("missing", 7));
        assertTrue(configService.getBool("missing", true));
    }

    @Test
    @DisplayName("[修复验证] getInt 遇到非整数不再静默截断，回退默认值")
    void getIntRejectsFraction() {
        when(configMapper.selectOne(any())).thenReturn(cfg("3.9"));
        assertEquals(5, configService.getInt("k", 5));
    }

    @Test
    @DisplayName("默认五因子权重和恰为 1")
    void defaultWeightsSumToOne() {
        when(configMapper.selectOne(any())).thenReturn(null);
        Map<String, BigDecimal> w = configService.matchWeights();
        assertEquals(5, w.size());
        BigDecimal sum = w.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, BigDecimal.ONE.compareTo(sum));
    }

    @Test
    @DisplayName("[修复验证] 权重和≠1 时归一化，负权重归零并回退默认")
    void weightsNormalizedAndValidated() {
        when(configMapper.selectOne(any())).thenReturn(cfg("0.3"));
        Map<String, BigDecimal> w = configService.matchWeights();
        BigDecimal sum = w.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, BigDecimal.ONE.compareTo(sum), "归一化后权重和应为 1");
        assertEquals(0, new BigDecimal("0.200000").compareTo(w.get("category")));

        when(configMapper.selectOne(any())).thenReturn(cfg("-1"));
        assertEquals(0, new BigDecimal("0.25").compareTo(configService.matchWeights().get("credit")),
                "全为负权重时回退默认权重");
    }

    @Test
    @DisplayName("[修复验证] 写入非法权重（和≠1）被拒绝")
    void illegalWeightsRejected() {
        when(configMapper.selectOne(any())).thenReturn(cfg("0.3"));
        BizException ex = assertThrows(BizException.class,
                () -> configService.update(Map.of("match_weight_price", "0.3")));
        assertTrue(ex.getMessage().contains("权重之和必须等于 1"));
    }

    @Test
    @DisplayName("[修复验证] 五项权重和=1 时允许写入")
    void legalWeightsAccepted() {
        when(configMapper.selectOne(any())).thenReturn(null);
        assertDoesNotThrow(() -> configService.update(Map.of(
                "match_weight_category", "0.30",
                "match_weight_price", "0.25",
                "match_weight_quantity", "0.15",
                "match_weight_credit", "0.20",
                "match_weight_distance", "0.10")));
    }
}
