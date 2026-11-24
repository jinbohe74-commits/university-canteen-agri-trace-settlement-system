package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.common.SqlLike;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.SourceVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 货源：农户维护、采购员货源大厅
 *
 * <p>修复 P2：所有关键词/筛选值改为参数绑定，剥离 LIKE 通配符；
 * 修复 P1-5：新增与编辑共用价格/数量校验。</p>
 */
@Service
@RequiredArgsConstructor
public class SourceService {

    private final SupplySourceMapper sourceMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final FarmerMapper farmerMapper;
    private final LogService logService;

    private void applyKeyword(LambdaQueryWrapper<SupplySource> qw, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return;
        }
        String like = SqlLike.contains(keyword);
        if (like == null) {
            qw.apply("1 = 0");
        } else {
            qw.apply("product_id IN (SELECT id FROM product WHERE name LIKE {0})", like);
        }
    }

    /** 农户自己的货源 */
    public PageResult<SourceVO> myPage(int page, int size, String keyword, String status) {
        LambdaQueryWrapper<SupplySource> qw = new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, UserContext.farmerId());
        applyKeyword(qw, keyword);
        if (StringUtils.hasText(status)) {
            qw.eq(SupplySource::getStatus, status);
            qw.orderByDesc(SupplySource::getId);
        } else {
            /* 不筛状态时，在售的排前面：以前统一按 id 倒序，历史售罄批次（同一品项
               反复发布、每次都被订空）会把在售货源压到第二页，看着像一堆重复死数据。 */
            qw.last("ORDER BY FIELD(status, '正常', '已售罄', '下架', '已撤回'), id DESC");
        }
        IPage<SupplySource> p = sourceMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p, this::toVO);
    }

    /** 货源大厅（采购员） */
    public PageResult<SourceVO> hall(int page, int size, String keyword, String category,
                                     BigDecimal priceMax, BigDecimal creditMin, String sort) {
        LambdaQueryWrapper<SupplySource> qw = new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getStatus, "正常").gt(SupplySource::getQuantity, 0);
        applyKeyword(qw, keyword);
        if (StringUtils.hasText(category) && !"全部".equals(category)) {
            ProductCategory big = categoryMapper.selectOne(new LambdaQueryWrapper<ProductCategory>()
                    .eq(ProductCategory::getCategoryName, category).eq(ProductCategory::getParentId, 0).last("LIMIT 1"));
            if (big != null) {
                qw.apply("product_id IN (SELECT id FROM product WHERE category_id IN " +
                                "(SELECT id FROM product_category WHERE parent_id = {0} OR id = {0}))",
                        big.getId());
            }
        }
        if (priceMax != null) {
            qw.le(SupplySource::getPrice, priceMax);
        }
        if (creditMin != null) {
            qw.apply("farmer_id IN (SELECT id FROM farmer WHERE credit_score >= {0})", creditMin);
        }
        if ("price".equals(sort)) {
            qw.orderByAsc(SupplySource::getPrice);
        } else if ("credit".equals(sort)) {
            qw.orderByAsc(SupplySource::getFarmerId);
        } else {
            qw.orderByDesc(SupplySource::getId);
        }
        IPage<SupplySource> p = sourceMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p, this::toVO);
    }

    public SourceVO toVO(SupplySource s) {
        SourceVO v = new SourceVO();
        org.springframework.beans.BeanUtils.copyProperties(s, v);
        Product p = productMapper.selectById(s.getProductId());
        if (p != null) {
            v.setProductName(p.getName());
            v.setProductNo(p.getProductNo());
            v.setAvgPrice(p.getAvgPrice());
            ProductCategory sub = categoryMapper.selectById(p.getCategoryId());
            if (sub != null) {
                ProductCategory big = (sub.getParentId() == null || sub.getParentId() == 0)
                        ? sub : categoryMapper.selectById(sub.getParentId());
                v.setCategoryName(big == null ? sub.getCategoryName() : big.getCategoryName());
            }
        }
        Farmer f = farmerMapper.selectById(s.getFarmerId());
        if (f != null) {
            v.setFarmerName(f.getName());
            v.setFarmerType(f.getType());
            v.setCreditScore(f.getCreditScore());
            v.setDistanceKm(f.getDistanceKm());
        }
        return v;
    }

    @Transactional
    public SupplySource save(SupplySource s) {
        Long farmerId = UserContext.farmerId();
        // 修复 P1-5：编辑分支同样强制校验，且拒绝 null（原来 null 可绕过校验）
        if (s.getQuantity() == null || s.getQuantity().signum() < 0) {
            throw new BizException(400, "可供数量不能为空或负数");
        }
        if (s.getPrice() == null || s.getPrice().signum() <= 0) {
            throw new BizException(400, "报价必须大于 0");
        }
        if (s.getProductId() == null) {
            throw new BizException(400, "请选择商品");
        }
        if (s.getId() == null) {
            s.setFarmerId(farmerId);
            s.setSoldQuantity(BigDecimal.ZERO);
            if (!StringUtils.hasText(s.getStatus())) {
                s.setStatus("正常");
            }
            if (s.getSupplyStart() == null) {
                s.setSupplyStart(LocalDate.now());
            }
            if (s.getSupplyEnd() == null) {
                s.setSupplyEnd(LocalDate.now().plusDays(15));
            }
            if (s.getUnit() == null) {
                s.setUnit("kg");
            }
            sourceMapper.insert(s);
            Product p = productMapper.selectById(s.getProductId());
            logService.record("货源管理", "新增货源 " + (p == null ? "" : p.getName())
                    + " " + s.getQuantity() + "kg @" + s.getPrice());
        } else {
            SupplySource old = sourceMapper.selectById(s.getId());
            if (old == null) {
                throw new BizException(404, "货源不存在");
            }
            if (!Objects.equals(old.getFarmerId(), farmerId)) {
                throw new BizException(403, "只能维护本农户的货源");
            }
            s.setFarmerId(farmerId);
            sourceMapper.updateById(s);
            logService.record("货源管理", "编辑货源 #" + s.getId());
        }
        return s;
    }

    @Transactional
    public void changeStatus(Long id, String status) {
        SupplySource s = sourceMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, "货源不存在");
        }
        if (!Objects.equals(s.getFarmerId(), UserContext.farmerId())) {
            throw new BizException(403, "只能维护本农户的货源");
        }
        s.setStatus(status);
        sourceMapper.updateById(s);
        logService.record("货源管理", "货源 #" + id + " 状态变更为 " + status);
    }

    public SupplySource detail(Long id) {
        SupplySource s = sourceMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, "货源不存在");
        }
        return s;
    }

    public List<SourceVO> byProduct(Long productId) {
        List<SupplySource> list = sourceMapper.selectList(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getProductId, productId).eq(SupplySource::getStatus, "正常")
                .gt(SupplySource::getQuantity, 0));
        return list.stream().map(this::toVO).toList();
    }
}
