package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

/**
 * 农产品档案
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final PriceHistoryMapper priceHistoryMapper;
    private final SupplySourceMapper sourceMapper;
    private final LogService logService;

    public PageResult<Map<String, Object>> page(int page, int size, String keyword, String category, String status) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(Product::getName, keyword).or().like(Product::getProductNo, keyword));
        }
        if (StringUtils.hasText(category) && !"全部".equals(category)) {
            Long bigId = categoryIdByName(category);
            if (bigId != null) {
                List<Long> subIds = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getParentId, bigId)).stream().map(ProductCategory::getId).toList();
                List<Long> ids = new ArrayList<>(subIds);
                ids.add(bigId);
                qw.in(Product::getCategoryId, ids);
            }
        }
        if (StringUtils.hasText(status)) {
            qw.eq(Product::getStatus, status);
        }
        qw.orderByAsc(Product::getId);
        IPage<Product> p = productMapper.selectPage(new Page<>(page, size), qw);
        Map<Long, ProductCategory> cats = new HashMap<>();
        for (ProductCategory c : categoryMapper.selectList(null)) {
            cats.put(c.getId(), c);
        }
        return PageResult.of(p, x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.getId());
            m.put("productNo", x.getProductNo());
            m.put("name", x.getName());
            m.put("categoryId", x.getCategoryId());
            ProductCategory sub = cats.get(x.getCategoryId());
            ProductCategory big = sub == null ? null
                    : (sub.getParentId() == null || sub.getParentId() == 0 ? sub : cats.get(sub.getParentId()));
            m.put("categoryName", big == null ? "-" : big.getCategoryName());
            m.put("subCategory", sub == null ? "-" : sub.getCategoryName());
            m.put("unit", x.getUnit());
            m.put("avgPrice", x.getAvgPrice());
            m.put("priceMin", x.getPriceMin());
            m.put("priceMax", x.getPriceMax());
            m.put("farmerCount", x.getFarmerCount());
            m.put("storageReq", x.getStorageReq());
            m.put("status", x.getStatus());
            m.put("description", x.getDescription());
            return m;
        });
    }

    private Long categoryIdByName(String name) {
        ProductCategory c = categoryMapper.selectOne(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getCategoryName, name).eq(ProductCategory::getParentId, 0).last("LIMIT 1"));
        return c == null ? null : c.getId();
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("categories", categoryMapper.selectCount(null));
        m.put("products", productMapper.selectCount(new LambdaQueryWrapper<Product>().eq(Product::getStatus, "在售")));
        m.put("total", productMapper.selectCount(null));
        m.put("farmers", productMapper.selectList(null).stream().mapToInt(Product::getFarmerCount).sum());
        long priceWarnings = productMapper.selectList(null).stream()
                .filter(p -> p.getPriceMax() != null && p.getPriceMin() != null
                        && p.getPriceMin().signum() > 0
                        && p.getPriceMax().subtract(p.getPriceMin())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(p.getPriceMin(), 0, java.math.RoundingMode.HALF_UP)
                        .compareTo(BigDecimal.valueOf(28)) > 0)
                .count();
        m.put("priceWarnings", priceWarnings);
        List<Map<String, Object>> tabs = new ArrayList<>();
        tabs.add(Map.of("name", "全部", "value", productMapper.selectCount(null)));
        for (String big : List.of("叶菜类", "根茎类", "茄果类", "瓜果类", "菌菇类", "有机菜")) {
            Long bid = categoryIdByName(big);
            if (bid == null) {
                continue;
            }
            List<Long> subIds = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                    .eq(ProductCategory::getParentId, bid)).stream().map(ProductCategory::getId).toList();
            List<Long> ids = new ArrayList<>(subIds);
            ids.add(bid);
            tabs.add(Map.of("name", big, "value", productMapper.selectCount(
                    new LambdaQueryWrapper<Product>().in(Product::getCategoryId, ids))));
        }
        m.put("tabs", tabs);
        return m;
    }

    public List<Map<String, Object>> categories() {
        List<ProductCategory> all = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .orderByAsc(ProductCategory::getSort));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProductCategory big : all) {
            if (big.getParentId() != null && big.getParentId() == 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", big.getId());
                m.put("name", big.getCategoryName());
                List<Map<String, Object>> children = new ArrayList<>();
                for (ProductCategory sub : all) {
                    if (big.getId().equals(sub.getParentId())) {
                        children.add(Map.of("id", sub.getId(), "name", sub.getCategoryName()));
                    }
                }
                m.put("children", children);
                out.add(m);
            }
        }
        return out;
    }

    public List<Product> listAll() {
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getStatus, "在售").orderByAsc(Product::getId));
    }

    public List<Map<String, Object>> priceHistory(Long productId) {
        List<PriceHistory> list = priceHistoryMapper.selectList(new LambdaQueryWrapper<PriceHistory>()
                .eq(PriceHistory::getProductId, productId).orderByAsc(PriceHistory::getStatDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PriceHistory h : list) {
            out.add(Map.of("date", h.getStatDate().toString(), "avg", h.getAvgPrice(),
                    "min", h.getMinPrice(), "max", h.getMaxPrice()));
        }
        return out;
    }

    @Transactional
    public Product save(Product p) {
        if (p.getId() == null) {
            if (!StringUtils.hasText(p.getProductNo())) {
                p.setProductNo("P9" + (500 + productMapper.selectCount(null) % 400));
            }
            if (p.getCategoryId() == null) {
                throw new BizException("请选择产品分类");
            }
            productMapper.insert(p);
            logService.record("农产品档案", "新增农产品 " + p.getName());
        } else {
            productMapper.updateById(p);
            logService.record("农产品档案", "编辑农产品 " + p.getName());
        }
        return p;
    }
}

