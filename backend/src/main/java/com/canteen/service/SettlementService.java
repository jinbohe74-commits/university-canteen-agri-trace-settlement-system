package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.SettlementVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 结算审核：生鲜损耗扣款核算、批量审核、对账凭证
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementMapper settlementMapper;
    private final SettlementItemMapper itemMapper;
    private final OrdersMapper ordersMapper;
    private final InboundRecordMapper inboundMapper;
    private final ProductMapper productMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final StatMapper statMapper;
    private final OrderService orderService;
    private final RefService refService;
    private final LogService logService;

    public PageResult<SettlementVO> page(int page, int size, String status, String period, String keyword) {
        LambdaQueryWrapper<Settlement> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            qw.eq(Settlement::getStatus, status);
        }
        if (StringUtils.hasText(period)) {
            qw.apply("DATE_FORMAT(create_time,'%Y-%m') = {0}", period);
        }
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(Settlement::getSettlementNo, keyword)
                    .or().inSql(Settlement::getOrderId, "SELECT id FROM orders WHERE order_no LIKE '%"
                            + keyword.replace("'", "") + "%'")
                    .or().inSql(Settlement::getFarmerId, "SELECT id FROM farmer WHERE name LIKE '%"
                            + keyword.replace("'", "") + "%'"));
        }
        Long canteenId = UserContext.getOrNull() == null ? null : UserContext.getOrNull().getCanteenId();
        if (canteenId != null && !UserContext.isAdmin()) {
            qw.eq(Settlement::getCanteenId, canteenId);
        }
        qw.orderByDesc(Settlement::getCreateTime);
        IPage<Settlement> p = settlementMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p, this::toVO);
    }

    public SettlementVO toVO(Settlement s) {
        SettlementVO v = new SettlementVO();
        org.springframework.beans.BeanUtils.copyProperties(s, v);
        Orders o = ordersMapper.selectById(s.getOrderId());
        if (o != null) {
            v.setOrderNo(o.getOrderNo());
            v.setUnitPrice(o.getUnitPrice());
            v.setOrderQuantity(o.getQuantity());
            Product p = productMapper.selectById(o.getProductId());
            if (p != null) {
                v.setProductName(p.getName());
                v.setUnit(p.getUnit());
            }
        }
        InboundRecord ir = inboundMapper.selectOne(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, s.getOrderId()).orderByDesc(InboundRecord::getId).last("LIMIT 1"));
        if (ir != null) {
            v.setReceiveQuantity(ir.getReceiveQuantity());
            v.setLossRate(ir.getLossRate());
        }
        Farmer f = farmerMapper.selectById(s.getFarmerId());
        v.setFarmerName(f == null ? "-" : f.getName());
        Canteen c = canteenMapper.selectById(s.getCanteenId());
        v.setCanteenName(c == null ? "-" : c.getName());
        v.setStatusName(s.getStatus());
        return v;
    }

    /** 顶部统计 */
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        long pending = settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStatus, "待审核"));
        BigDecimal pendingAmount = BigDecimal.ZERO;
        for (Settlement s : settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStatus, "待审核"))) {
            pendingAmount = pendingAmount.add(s.getTotalAmount());
        }
        m.put("pendingCount", pending);
        m.put("pendingAmount", pendingAmount);
        m.put("settledCount", settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStatus, "已结算")
                .apply("DATE_FORMAT(create_time,'%Y-%m') = DATE_FORMAT(NOW(),'%Y-%m')")));
        Map<String, Object> month = statMapper.monthSettlement();
        m.put("monthTotal", month == null ? 0 : month.get("total"));
        m.put("monthLoss", month == null ? 0 : month.get("loss"));
        m.put("monthActual", month == null ? 0 : month.get("actual"));
        m.put("monthCount", month == null ? 0 : month.get("cnt"));
        m.put("statusCount", statMapper.settlementStatusCount());
        return m;
    }

    public Map<String, Object> detail(Long id) {
        Settlement s = settlementMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, "结算单不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settlement", toVO(s));
        m.put("items", itemMapper.selectList(new LambdaQueryWrapper<SettlementItem>()
                .eq(SettlementItem::getSettlementId, id)));
        Orders o = ordersMapper.selectById(s.getOrderId());
        m.put("order", o == null ? null : refService.toOrderVO(o));
        m.put("rules", List.of(
                Map.of("name", "订单金额", "desc", "下单数量 × 成交单价"),
                Map.of("name", "损耗扣款", "desc", "损耗数量 × 成交单价"),
                Map.of("name", "质检扣款", "desc", "不合格部分按约定比例"),
                Map.of("name", "实际结算金额", "desc", "订单金额 − 损耗扣款 − 质检扣款")));
        InboundRecord ir = inboundMapper.selectOne(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, s.getOrderId()).orderByDesc(InboundRecord::getId).last("LIMIT 1"));
        m.put("inbound", ir);
        return m;
    }

    /** 审核（支持批量） */
    @Transactional
    public Map<String, Object> audit(List<Long> ids, boolean pass, String remark) {
        int ok = 0;
        int rejected = 0;
        for (Long id : ids) {
            Settlement s = settlementMapper.selectById(id);
            if (s == null || !"待审核".equals(s.getStatus())) {
                continue;
            }
            s.setStatus(pass ? "已结算" : "已驳回");
            s.setAuditor(UserContext.get().getRealName());
            s.setConfirmTime(LocalDateTime.now());
            s.setRemark(StringUtils.hasText(remark) ? remark : s.getRemark());
            settlementMapper.updateById(s);
            if (pass) {
                ok++;
                Orders o = ordersMapper.selectById(s.getOrderId());
                if (o != null && o.getStatus() == 80) {
                    orderService.changeStatus(o.getId(), 90, o.getVersion());
                }
            } else {
                rejected++;
            }
            logService.record("结算管理", "审核结算单 " + s.getSettlementNo() + (pass ? "（通过）" : "（驳回）"));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("passed", ok);
        res.put("rejected", rejected);
        return res;
    }

    /** 农户端：我的结算 */
    public PageResult<SettlementVO> farmerPage(int page, int size, String status) {
        LambdaQueryWrapper<Settlement> qw = new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getFarmerId, UserContext.farmerId());
        if (StringUtils.hasText(status)) {
            qw.eq(Settlement::getStatus, status);
        }
        qw.orderByDesc(Settlement::getCreateTime);
        IPage<Settlement> p = settlementMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p, this::toVO);
    }

    public List<Settlement> byOrder(Long orderId) {
        return settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getOrderId, orderId));
    }

    public static String month() {
        return LocalDateTimestr();
    }

    private static String LocalDateTimestr() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}
