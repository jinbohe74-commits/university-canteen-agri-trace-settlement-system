package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.DemandVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 采购需求
 */
@Service
@RequiredArgsConstructor
public class DemandService {

    private final PurchaseDemandMapper demandMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final CanteenMapper canteenMapper;
    private final OrdersMapper ordersMapper;
    private final SupplySourceMapper sourceMapper;
    private final LogService logService;

    public PageResult<DemandVO> page(int page, int size, String keyword, String status) {
        LambdaQueryWrapper<PurchaseDemand> qw = new LambdaQueryWrapper<>();
        Long canteenId = UserContext.getOrNull() == null ? null : UserContext.getOrNull().getCanteenId();
        if (canteenId != null && !UserContext.isAdmin()) {
            qw.eq(PurchaseDemand::getCanteenId, canteenId);
        }
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(PurchaseDemand::getDemandNo, keyword)
                    .or().inSql(PurchaseDemand::getProductId,
                            "SELECT id FROM product WHERE name LIKE '%" + keyword.replace("'", "") + "%'"));
        }
        if (StringUtils.hasText(status)) {
            qw.eq(PurchaseDemand::getStatus, status);
        }
        qw.orderByDesc(PurchaseDemand::getCreateTime);
        IPage<PurchaseDemand> p = demandMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p, this::toVO);
    }

    public DemandVO toVO(PurchaseDemand d) {
        DemandVO v = new DemandVO();
        org.springframework.beans.BeanUtils.copyProperties(d, v);
        Product p = productMapper.selectById(d.getProductId());
        if (p != null) {
            v.setProductName(p.getName());
            v.setUnit(p.getUnit());
            ProductCategory sub = categoryMapper.selectById(p.getCategoryId());
            if (sub != null) {
                ProductCategory big = (sub.getParentId() == null || sub.getParentId() == 0)
                        ? sub : categoryMapper.selectById(sub.getParentId());
                v.setCategoryName(big == null ? sub.getCategoryName() : big.getCategoryName());
            }
        }
        Canteen c = canteenMapper.selectById(d.getCanteenId());
        v.setCanteenName(c == null ? "-" : c.getName());
        if (p != null) {
            v.setMatchCount(sourceMapper.selectCount(new LambdaQueryWrapper<SupplySource>()
                    .eq(SupplySource::getProductId, p.getId()).eq(SupplySource::getStatus, "正常")
                    .gt(SupplySource::getQuantity, 0)).intValue());
        }
        Orders o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getDemandId, d.getId()).orderByDesc(Orders::getId).last("LIMIT 1"));
        if (o != null) {
            v.setOrderId(o.getId());
            v.setOrderNo(o.getOrderNo());
        }
        return v;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        Long canteenId = UserContext.getOrNull() == null ? null : UserContext.getOrNull().getCanteenId();
        LambdaQueryWrapper<PurchaseDemand> base = new LambdaQueryWrapper<>();
        if (canteenId != null && !UserContext.isAdmin()) {
            base.eq(PurchaseDemand::getCanteenId, canteenId);
        }
        m.put("total", demandMapper.selectCount(base));
        m.put("matching", demandMapper.selectCount(new LambdaQueryWrapper<PurchaseDemand>()
                .eq(canteenId != null, PurchaseDemand::getCanteenId, canteenId).eq(PurchaseDemand::getStatus, "处理中")));
        m.put("dealt", demandMapper.selectCount(new LambdaQueryWrapper<PurchaseDemand>()
                .eq(canteenId != null, PurchaseDemand::getCanteenId, canteenId)
                .in(PurchaseDemand::getStatus, List.of("已成交", "已下单", "已完成"))));
        m.put("withdrawn", demandMapper.selectCount(new LambdaQueryWrapper<PurchaseDemand>()
                .eq(canteenId != null, PurchaseDemand::getCanteenId, canteenId).eq(PurchaseDemand::getStatus, "已撤回")));
        List<PurchaseDemand> all = demandMapper.selectList(base);
        int avgQuotes = all.isEmpty() ? 0 : (int) Math.round(all.stream()
                .mapToInt(x -> x.getQuoteCount() == null ? 0 : x.getQuoteCount()).average().orElse(0));
        m.put("avgQuotes", avgQuotes);
        return m;
    }

    @Transactional
    public PurchaseDemand save(PurchaseDemand d) {
        if (d.getDeadline() != null && d.getDeadline().isBefore(LocalDateTime.now())) {
            throw new BizException("期望交货时间不能早于当前时间");
        }
        if (d.getQuantity() == null || d.getQuantity().signum() <= 0) {
            throw new BizException("需求数量必须大于 0");
        }
        if (d.getId() == null) {
            d.setCanteenId(UserContext.canteenId());
            String xp = "X" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            d.setDemandNo(com.canteen.common.Codes.next(xp, 3, (int) (demandMapper.selectCount(null) % 800 + 100),
                    code -> demandMapper.selectCount(new LambdaQueryWrapper<PurchaseDemand>().eq(PurchaseDemand::getDemandNo, code)) > 0));
            d.setStatus("处理中");
            d.setQuoteCount((int) Math.max(1L, sourceMapper.selectCount(new LambdaQueryWrapper<SupplySource>()
                    .eq(SupplySource::getProductId, d.getProductId()).eq(SupplySource::getStatus, "正常"))));
            demandMapper.insert(d);
            Product p = productMapper.selectById(d.getProductId());
            logService.record("采购需求", "发布需求 " + d.getDemandNo() + "（"
                    + (p == null ? "" : p.getName()) + " " + d.getQuantity() + "kg）");
        } else {
            PurchaseDemand old = demandMapper.selectById(d.getId());
            if (old == null) {
                throw new BizException(404, "需求不存在");
            }
            if (!Objects.equals(old.getCanteenId(), UserContext.canteenId())) {
                throw new BizException(403, "只能维护本食堂的需求");
            }
            d.setDemandNo(old.getDemandNo());
            d.setCanteenId(old.getCanteenId());
            demandMapper.updateById(d);
            logService.record("采购需求", "编辑需求 " + d.getDemandNo());
        }
        return d;
    }

    @Transactional
    public void withdraw(Long id) {
        PurchaseDemand d = demandMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "需求不存在");
        }
        if (!Objects.equals(d.getCanteenId(), UserContext.canteenId())) {
            throw new BizException(403, "只能维护本食堂的需求");
        }
        if (!"处理中".equals(d.getStatus())) {
            throw new BizException("仅处理中的需求可以撤回");
        }
        d.setStatus("已撤回");
        demandMapper.updateById(d);
        logService.record("采购需求", "撤回需求 " + d.getDemandNo());
    }

    public PurchaseDemand getById(Long id) {
        PurchaseDemand d = demandMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "需求不存在");
        }
        return d;
    }
}


