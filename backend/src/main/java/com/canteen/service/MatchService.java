package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.MatchVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 五因子加权智能匹配：品类 / 价格 / 数量 / 信用 / 距离·历史合作
 * 权重来自 sys_config，可在系统参数中调整
 */
@Service
@RequiredArgsConstructor
public class MatchService {

    private final PurchaseDemandMapper demandMapper;
    private final SupplySourceMapper sourceMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final ConfigService configService;
    private final DemandService demandService;
    private final ProductService productService;

    public Map<String, Object> match(Long demandId) {
        PurchaseDemand d = demandService.getById(demandId);
        Product product = productMapper.selectById(d.getProductId());
        if (product == null) {
            throw new com.canteen.common.BizException(404, "需求关联的农产品不存在");
        }
        Map<String, BigDecimal> w = configService.matchWeights();
        int topN = configService.getInt("match_top_n", 5);

        // 同类目有效货源
        List<SupplySource> sources = sourceMapper.selectList(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getStatus, "正常")
                .gt(SupplySource::getQuantity, 0)
                .inSql(SupplySource::getProductId,
                        "SELECT id FROM product WHERE category_id = " + product.getCategoryId()
                                + " OR category_id IN (SELECT id FROM product_category WHERE parent_id = "
                                + product.getCategoryId() + ")"));
        List<Farmer> farmers = farmerMapper.selectList(null);
        Map<Long, Farmer> farmerMap = new HashMap<>();
        for (Farmer f : farmers) {
            farmerMap.put(f.getId(), f);
        }
        Map<Long, Product> productMap = new HashMap<>();
        for (Product p : productService.listAll()) {
            productMap.put(p.getId(), p);
        }
        if (sources.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("demand", demandService.toVO(d));
            empty.put("weights", w);
            empty.put("results", List.of());
            empty.put("validCount", 0);
            return empty;
        }
        // 价格归一化区间
        BigDecimal minPrice = sources.stream().map(SupplySource::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal maxPrice = sources.stream().map(SupplySource::getPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ONE);

        List<MatchVO> list = new ArrayList<>();
        for (SupplySource s : sources) {
            Farmer f = farmerMap.get(s.getFarmerId());
            if (f == null || !"已认证".equals(f.getStatus())) {
                continue;
            }
            MatchVO v = new MatchVO();
            v.setSourceId(s.getId());
            v.setFarmerId(s.getFarmerId());
            v.setFarmerName(f.getName());
            v.setFarmerType(f.getType());
            v.setUnit(s.getUnit());
            v.setPrice(s.getPrice());
            v.setQuantity(s.getQuantity());
            v.setCreditScore(f.getCreditScore());
            v.setDistanceKm(f.getDistanceKm());
            v.setDealCount(f.getDealCount());
            Product p = productMap.get(s.getProductId());
            v.setProductName(p == null ? product.getName() : p.getName());

            // 1) 品类匹配度
            double categoryScore = (p != null && Objects.equals(p.getCategoryId(), product.getCategoryId()))
                    ? 100 : (p != null && sameBigCategory(p.getCategoryId(), product.getCategoryId()) ? 85 : 60);
            // 2) 价格竞争力：同批次内越便宜得分越高
            double priceScore;
            if (maxPrice.compareTo(minPrice) == 0) {
                priceScore = 100;
            } else {
                double ratio = s.getPrice().subtract(minPrice)
                        .divide(maxPrice.subtract(minPrice), 4, RoundingMode.HALF_UP).doubleValue();
                priceScore = 100 - Math.min(1, Math.max(0, ratio)) * 30;
            }
            // 3) 数量满足度
            double qtyRatio = s.getQuantity().divide(d.getQuantity(), 4, RoundingMode.HALF_UP).doubleValue();
            double quantityScore = qtyRatio >= 1 ? 100 : Math.max(0, Math.min(100, qtyRatio * 100));
            // 4) 信用因子
            double creditScore = f.getCreditScore() == null ? 80 : f.getCreditScore().doubleValue();
            // 5) 距离 / 历史合作
            double dist = f.getDistanceKm() == null ? 10 : f.getDistanceKm().doubleValue();
            double distanceScore = Math.max(60, 100 - dist * 1.2);

            double total = w.get("category").doubleValue() * categoryScore
                    + w.get("price").doubleValue() * priceScore
                    + w.get("quantity").doubleValue() * quantityScore
                    + w.get("credit").doubleValue() * creditScore
                    + w.get("distance").doubleValue() * distanceScore;

            v.setCategoryScore(money(categoryScore));
            v.setPriceScore(money(priceScore));
            v.setQuantityScore(money(quantityScore));
            v.setCreditFactor(money(creditScore));
            v.setDistanceScore(money(distanceScore));
            v.setScore(money(total));
            v.setQuantityEnough(qtyRatio >= 1);
            list.add(v);
        }
        list.sort(Comparator.comparing(MatchVO::getScore).reversed());
        List<MatchVO> top = new ArrayList<>();
        for (int i = 0; i < Math.min(topN, list.size()); i++) {
            MatchVO v = list.get(i);
            v.setRank(i + 1);
            v.setBest(i == 0);
            top.add(v);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("demand", demandService.toVO(d));
        res.put("product", product);
        res.put("weights", w);
        res.put("weightSum", w.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        res.put("results", top);
        res.put("validCount", list.size());
        res.put("formula", "综合得分 = W1×品类 + W2×价格 + W3×数量 + W4×信用 + W5×距离/历史");
        return res;
    }

    private boolean sameBigCategory(Long c1, Long c2) {
        ProductCategory a = categoryMapper.selectById(c1);
        ProductCategory b = categoryMapper.selectById(c2);
        if (a == null || b == null) {
            return false;
        }
        Long pa = (a.getParentId() == null || a.getParentId() == 0) ? a.getId() : a.getParentId();
        Long pb = (b.getParentId() == null || b.getParentId() == 0) ? b.getId() : b.getParentId();
        return Objects.equals(pa, pb);
    }

    private BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }
}
