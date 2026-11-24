package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.LoginUser;
import com.canteen.common.PageResult;
import com.canteen.common.SqlLike;
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
 *
 * <p>修复：P1-3 详情归属校验、P1-12 驳回后订单回退可重新入库、
 * P2 关键词参数绑定 / 数据权限按角色 / 审核改 CAS 防并发重复审核。</p>
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
    private final NotificationService notificationService;

    private void applyScope(LambdaQueryWrapper<Settlement> qw) {
        LoginUser lu = UserContext.getOrNull();
        if (lu == null || "ADMIN".equals(lu.getRole())) {
            return;
        }
        if ("PURCHASER".equals(lu.getRole())) {
            Long cid = lu.getCanteenId();
            qw.eq(Settlement::getCanteenId, cid == null ? -1L : cid);
        } else if ("FARMER".equals(lu.getRole())) {
            Long fid = lu.getFarmerId();
            qw.eq(Settlement::getFarmerId, fid == null ? -1L : fid);
        }
    }

    /** 修复 P1-3：带 id 的结算详情补归属校验。 */
    private void assertScope(Settlement s) {
        LoginUser lu = UserContext.getOrNull();
        if (lu == null || "ADMIN".equals(lu.getRole())) {
            return;
        }
        if ("PURCHASER".equals(lu.getRole())) {
            Long cid = lu.getCanteenId();
            if (cid == null || !Objects.equals(s.getCanteenId(), cid)) {
                throw new BizException(403, "无权访问其他食堂的结算单");
            }
        } else if ("FARMER".equals(lu.getRole())
                && !Objects.equals(s.getFarmerId(), lu.getFarmerId())) {
            throw new BizException(403, "无权访问其他农户的结算单");
        }
    }

    public PageResult<SettlementVO> page(int page, int size, String status, String period, String keyword) {
        LambdaQueryWrapper<Settlement> qw = new LambdaQueryWrapper<>();
        applyScope(qw);
        if (StringUtils.hasText(status)) {
            qw.eq(Settlement::getStatus, status);
        }
        if (StringUtils.hasText(period)) {
            qw.apply("DATE_FORMAT(create_time,'%Y-%m') = {0}", period);
        }
        if (StringUtils.hasText(keyword)) {
            String like = SqlLike.contains(keyword);
            if (like == null) {
                qw.apply("1 = 0");
            } else {
                qw.and(w -> w.apply("settlement_no LIKE {0}", like)
                        .or().apply("order_id IN (SELECT id FROM orders WHERE order_no LIKE {0})", like)
                        .or().apply("farmer_id IN (SELECT id FROM farmer WHERE name LIKE {0})", like));
            }
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
        assertScope(s);
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

    /**
     * 审核（支持批量）。修复 P1-12：驳回时把订单原子回退到「已送达(60)」，
     * 配合 InboundService 复用「已驳回」结算单，形成可重新入库的闭环。
     */
    @Transactional
    public Map<String, Object> audit(List<Long> ids, boolean pass, String remark) {
        int ok = 0;
        int rejected = 0;
        for (Long id : ids) {
            Settlement s = settlementMapper.selectById(id);
            if (s == null || !"待审核".equals(s.getStatus())) {
                continue;
            }
            // CAS 更新：只有仍为「待审核」才能改，避免两个管理员并发重复审核
            int rows = settlementMapper.update(null, new UpdateWrapper<Settlement>()
                    .set("status", pass ? "已结算" : "已驳回")
                    .set("auditor", UserContext.get().getRealName())
                    .set("confirm_time", LocalDateTime.now())
                    .set(StringUtils.hasText(remark), "remark", remark)
                    .eq("id", id).eq("status", "待审核"));
            if (rows == 0) {
                continue;
            }
            Orders o = ordersMapper.selectById(s.getOrderId());
            if (pass) {
                ok++;
                if (o != null && o.getStatus() == 80) {
                    orderService.changeStatus(o.getId(), 90, o.getVersion());
                }
            } else {
                rejected++;
                if (o != null && o.getStatus() == 80) {
                    ordersMapper.reopenForInbound(o.getId(), o.getVersion());
                }
            }
            logService.record("结算管理", "审核结算单 " + s.getSettlementNo() + (pass ? "（通过）" : "（驳回）"));
            if (o != null) {
                notificationService.toOrderParties(o.getId(), "SETTLE", s.getSettlementNo(),
                        pass ? "结算已通过" : "结算被驳回",
                        "结算单 " + s.getSettlementNo() + (pass ? " 审核通过，订单已完成结算"
                                : " 审核驳回，原因：" + (remark == null ? "未说明" : remark) + "，请重新确认入库"),
                        pass ? Map.of("FARMER", "/farmer/settlement")
                             : Map.of("FARMER", "/farmer/settlement", "CANTEEN", "/purchaser/inbound"));
            }
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
