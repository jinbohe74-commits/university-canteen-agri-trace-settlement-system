package com.canteen.unit;

import com.canteen.common.BizException;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.service.ConfigService;
import com.canteen.service.DemandService;
import com.canteen.service.MatchService;
import com.canteen.service.ProductService;
import com.canteen.vo.MatchVO;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/** 五因子智能匹配打分逻辑测试。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MatchServiceUnitTest {

    @Mock private PurchaseDemandMapper demandMapper;
    @Mock private SupplySourceMapper sourceMapper;
    @Mock private ProductMapper productMapper;
    @Mock private ProductCategoryMapper categoryMapper;
    @Mock private FarmerMapper farmerMapper;
    @Mock private CanteenMapper canteenMapper;
    @Mock private ConfigService configService;
    @Mock private DemandService demandService;
    @Mock private ProductService productService;
    @InjectMocks private MatchService matchService;

    private static Map<String, BigDecimal> weights(double each) {
        Map<String, BigDecimal> w = new LinkedHashMap<>();
        w.put("category", BigDecimal.valueOf(each));
        w.put("price", BigDecimal.valueOf(each));
        w.put("quantity", BigDecimal.valueOf(each));
        w.put("credit", BigDecimal.valueOf(each));
        w.put("distance", BigDecimal.valueOf(each));
        return w;
    }

    private static Map<String, BigDecimal> defaultWeights() {
        Map<String, BigDecimal> w = new LinkedHashMap<>();
        w.put("category", new BigDecimal("0.20"));
        w.put("price", new BigDecimal("0.25"));
        w.put("quantity", new BigDecimal("0.15"));
        w.put("credit", new BigDecimal("0.25"));
        w.put("distance", new BigDecimal("0.15"));
        return w;
    }

    @BeforeEach
    void setUp() {
        PurchaseDemand d = new PurchaseDemand();
        d.setId(1L);
        d.setProductId(3L);
        d.setQuantity(new BigDecimal("200"));

        Product p = new Product();
        p.setId(3L);
        p.setName("大白菜");
        p.setCategoryId(7L);

        SupplySource s = new SupplySource();
        s.setId(9L);
        s.setFarmerId(2L);
        s.setProductId(3L);
        s.setQuantity(new BigDecimal("400"));
        s.setPrice(new BigDecimal("1.80"));
        s.setStatus("正常");

        Farmer f = new Farmer();
        f.setId(2L);
        f.setName("绿源蔬菜合作社");
        f.setStatus("已认证");
        f.setCreditScore(new BigDecimal("92"));
        f.setDistanceKm(new BigDecimal("10"));
        f.setDealCount(20);

        when(demandService.getById(any())).thenReturn(d);
        when(productMapper.selectById(any())).thenReturn(p);
        when(sourceMapper.selectList(any())).thenReturn(List.of(s));
        when(farmerMapper.selectList(any())).thenReturn(List.of(f));
        when(productService.listAll()).thenReturn(List.of(p));
        when(configService.getInt(eq("match_top_n"), anyInt())).thenReturn(5);
    }

    private double topScore(Map<String, BigDecimal> w) {
        when(configService.matchWeights()).thenReturn(w);
        @SuppressWarnings("unchecked")
        Map<String, Object> res = matchService.match(1L);
        @SuppressWarnings("unchecked")
        List<MatchVO> results = (List<MatchVO>) res.get("results");
        assertEquals(1, results.size());
        return results.get(0).getScore().doubleValue();
    }

    @Test
    @DisplayName("默认权重下综合得分落在 0~100，且 weightSum=1")
    void normalScoreWithinRange() {
        when(configService.matchWeights()).thenReturn(defaultWeights());
        @SuppressWarnings("unchecked")
        Map<String, Object> res = matchService.match(1L);
        @SuppressWarnings("unchecked")
        List<MatchVO> results = (List<MatchVO>) res.get("results");
        assertEquals(1, results.size());
        MatchVO top = results.get(0);
        assertTrue(top.getScore().doubleValue() <= 100 && top.getScore().doubleValue() >= 0);
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) res.get("weightSum")));
        assertTrue(top.getBest());
        assertEquals(Integer.valueOf(1), top.getRank());
    }

    @Test
    @DisplayName("[修复验证] 权重和 1.5 时自动归一化，得分仍不超过 100")
    void weightSumNotOneIsNormalized() {
        double score = topScore(weights(0.3));
        assertEquals(96.0, score, 0.01, "144 / 1.5 = 96");
        assertTrue(score <= 100);
    }

    @Test
    @DisplayName("[修复验证] 需求数量为 0 时返回业务校验错误，不再 500")
    void zeroDemandQuantityRejected() {
        PurchaseDemand zero = new PurchaseDemand();
        zero.setId(1L);
        zero.setProductId(3L);
        zero.setQuantity(BigDecimal.ZERO);
        when(demandService.getById(any())).thenReturn(zero);
        when(configService.matchWeights()).thenReturn(defaultWeights());
        BizException ex = assertThrows(BizException.class, () -> matchService.match(1L));
        assertTrue(ex.getMessage().contains("数量"));
    }

    @Test
    @DisplayName("[修复验证] 负权重被归零并回退默认，得分不再为负")
    void negativeWeightsRejected() {
        double score = topScore(weights(-0.2));
        assertTrue(score >= 0);
    }
}
