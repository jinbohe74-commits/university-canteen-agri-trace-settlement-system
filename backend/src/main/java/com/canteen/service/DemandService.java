package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.LoginUser;
import com.canteen.common.PageResult;
import com.canteen.common.SqlLike;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.DemandVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 采购需求
 *
 * <p>修复：P1-3 详情归属校验、P1-8 数量与价格校验、P2 关键词参数绑定 /
 * 未绑定食堂的非管理员不再返回全量数据。</p>
 */
@Service
@RequiredArgsConstructor
public class DemandService {

    private static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");

    private final PurchaseDemandMapper demandMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final CanteenMapper canteenMapper;
    private final OrdersMapper ordersMapper;
    private final SupplySourceMapper sourceMapper;
    private final LogService logService;

    /** 修复 P2：数据权限按角色判定，采购员未绑定食堂时返回空集而不是全量。 */
    private void applyScope(LambdaQueryWrapper<PurchaseDemand> qw) {
        LoginUser lu = UserContext.getOrNull();
        if (lu == null || "ADMIN".equals(lu.getRole())) {
            return;
        }
        if ("PURCHASER".equals(lu.getRole())) {
            Long canteenId = lu.getCanteenId();
            qw.eq(PurchaseDemand::getCanteenId, canteenId == null ? -1L : canteenId);
        }
    }

    public PageResult<DemandVO> page(int page, int size, String keyword, String status) {
        LambdaQueryWrapper<PurchaseDemand> qw = new LambdaQueryWrapper<>();
        applyScope(qw);
        if (StringUtils.hasText(keyword)) {
            String like = SqlLike.contains(keyword);
            if (like == null) {
                qw.apply("1 = 0");
            } else {
                qw.and(w -> w.apply("demand_no LIKE {0}", like)
                        .or().apply("product_id IN (SELECT id FROM product WHERE name LIKE {0})", like));
            }
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
        LambdaQueryWrapper<PurchaseDemand> base = new LambdaQueryWrapper<>();
        applyScope(base);
        m.put("total", demandMapper.selectCount(base));
        m.put("matching", demandMapper.selectCount(matchingQw("处理中")));
        LambdaQueryWrapper<PurchaseDemand> dealt = new LambdaQueryWrapper<PurchaseDemand>()
                .in(PurchaseDemand::getStatus, List.of("已成交", "已下单", "已完成"));
        applyScope(dealt);
        m.put("dealt", demandMapper.selectCount(dealt));
        LambdaQueryWrapper<PurchaseDemand> withdrawn = new LambdaQueryWrapper<PurchaseDemand>()
                .eq(PurchaseDemand::getStatus, "已撤回");
        applyScope(withdrawn);
        m.put("withdrawn", demandMapper.selectCount(withdrawn));
        List<PurchaseDemand> all = demandMapper.selectList(base);
        int avgQuotes = all.isEmpty() ? 0 : (int) Math.round(all.stream()
                .mapToInt(x -> x.getQuoteCount() == null ? 0 : x.getQuoteCount()).average().orElse(0));
        m.put("avgQuotes", avgQuotes);
        return m;
    }

    private LambdaQueryWrapper<PurchaseDemand> matchingQw(String status) {
        LambdaQueryWrapper<PurchaseDemand> qw = new LambdaQueryWrapper<PurchaseDemand>()
                .eq(PurchaseDemand::getStatus, status);
        applyScope(qw);
        return qw;
    }

    private void validate(PurchaseDemand d) {
        if (d.getDeadline() != null && d.getDeadline().isBefore(LocalDateTime.now())) {
            throw new BizException(400, "期望交货时间不能早于当前时间");
        }
        // 修复 P1-8：新增与编辑共用校验，杜绝数量为 0 触发匹配除零
        if (d.getQuantity() == null || d.getQuantity().signum() <= 0) {
            throw new BizException(400, "需求数量必须大于 0");
        }
        if (d.getQuantity().compareTo(MAX_QUANTITY) > 0) {
            throw new BizException(400, "需求数量超出上限");
        }
        if (d.getExpectedPrice() != null && d.getExpectedPrice().signum() < 0) {
            throw new BizException(400, "期望价格不能为负数");
        }
        if (d.getProductId() == null) {
            throw new BizException(400, "请选择采购商品");
        }
    }

    @Transactional
    public PurchaseDemand save(PurchaseDemand d) {
        validate(d);
        if (d.getId() == null) {
            d.setCanteenId(UserContext.canteenId());
            String xp = "X" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            d.setDemandNo(com.canteen.common.Codes.next(xp, 3, (int) (demandMapper.selectCount(null) % 800 + 100),
                    code -> demandMapper.selectCount(new LambdaQueryWrapper<PurchaseDemand>().eq(PurchaseDemand::getDemandNo, code)) > 0));
            d.setStatus("处理中");
            // 报价数一律从 0 起算，由农户真实报价累加（原实现按在售货源数预估，是假数据）
            d.setQuoteCount(0);
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

    /** 修复 P1-3：带 id 的详情接口补归属校验，杜绝跨食堂越权（IDOR）。 */
    public PurchaseDemand getById(Long id) {
        PurchaseDemand d = demandMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "需求不存在");
        }
        LoginUser lu = UserContext.getOrNull();
        if (lu != null && "PURCHASER".equals(lu.getRole())) {
            Long canteenId = lu.getCanteenId();
            if (canteenId == null || !Objects.equals(d.getCanteenId(), canteenId)) {
                throw new BizException(403, "无权访问其他食堂的需求");
            }
        }
        return d;
    }
}
