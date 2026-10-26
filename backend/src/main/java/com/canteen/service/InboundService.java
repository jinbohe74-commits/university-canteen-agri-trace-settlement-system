package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
import com.canteen.common.UserContext;
import com.canteen.dto.InboundDTO;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 入库确认：按实收数量自动核算生鲜损耗，并联动生成结算单
 */
@Service
@RequiredArgsConstructor
public class InboundService {

    private final OrdersMapper ordersMapper;
    private final InboundRecordMapper inboundMapper;
    private final OrderItemMapper itemMapper;
    private final SettlementMapper settlementMapper;
    private final SettlementItemMapper settlementItemMapper;
    private final QualityReportMapper qualityMapper;
    private final ProductMapper productMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final RefService refService;
    private final OrderService orderService;
    private final CreditService creditService;
    private final ConfigService configService;
    private final TraceService traceService;
    private final LogService logService;

    /** 入库确认页数据（订单 + 批次 + 结算预览） */
    public Map<String, Object> preview(String orderNo) {
        Orders o;
        if (orderNo == null || orderNo.isBlank()) {
            // 默认取当前食堂最近一笔“已送达”订单
            o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                    .eq(Orders::getCanteenId, UserContext.get().getCanteenId() == null ? 1L : UserContext.get().getCanteenId())
                    .eq(Orders::getStatus, 60).orderByDesc(Orders::getCreateTime).last("LIMIT 1"));
        } else {
            o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                    .and(w -> w.eq(Orders::getOrderNo, orderNo).or().eq(Orders::getBatchNo, orderNo))
                    .last("LIMIT 1"));
        }
        if (o == null) {
            throw new BizException(404, "未找到待入库订单，请检查订单号或批次号");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", refService.toOrderVO(o));
        QualityReport qr = qualityMapper.selectOne(new LambdaQueryWrapper<QualityReport>()
                .eq(QualityReport::getOrderId, o.getId()).orderByDesc(QualityReport::getId).last("LIMIT 1"));
        m.put("quality", qr);
        m.put("batchNo", o.getBatchNo() == null ? (qr == null ? "" : qr.getBatchNo()) : o.getBatchNo());
        BigDecimal lossRateThreshold = BigDecimal.valueOf(configService.getDouble("warn_loss_rate", 5));
        m.put("lossRateThreshold", lossRateThreshold);
        m.put("canInbound", o.getStatus() == 60);
        // 送数量默认值 = 下单数量
        m.put("receiveQuantity", o.getQuantity());
        m.put("lossQuantity", BigDecimal.ZERO);
        m.put("lossRate", BigDecimal.ZERO);
        m.put("orderAmount", o.getTotalAmount());
        m.put("actualAmount", o.getTotalAmount());
        m.put("lossDeduction", BigDecimal.ZERO);
        m.put("recent", recent(5));
        return m;
    }

    /** 入库确认：生成入库记录 + 结算单，订单流转至待结算 */
    @Transactional
    public Map<String, Object> confirm(InboundDTO dto) {
        Orders o = ordersMapper.selectById(dto.getOrderId());
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        if (o.getStatus() != 60) {
            throw new BizException("仅【已送达】订单可入库，当前状态：" + RefService.statusName(o.getStatus()));
        }
        BigDecimal orderQty = o.getQuantity();
        BigDecimal receive = dto.getReceiveQuantity();
        if (receive == null || receive.signum() <= 0) {
            throw new BizException("实收数量必须大于 0");
        }
        if (receive.compareTo(orderQty) > 0) {
            throw new BizException("实收数量不能大于下单数量（支持部分收货，损耗数量自动核算）");
        }
        BigDecimal loss = orderQty.subtract(receive).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lossRate = orderQty.signum() == 0 ? BigDecimal.ZERO
                : loss.multiply(BigDecimal.valueOf(100)).divide(orderQty, 2, RoundingMode.HALF_UP);
        BigDecimal price = o.getUnitPrice();
        BigDecimal orderAmount = orderQty.multiply(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lossDeduction = loss.multiply(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal deduction = BigDecimal.ZERO;
        if ("不合格".equals(dto.getQualityResult())) {
            deduction = orderAmount.multiply(new BigDecimal("0.2")).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal actual = orderAmount.subtract(lossDeduction).subtract(deduction)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        InboundRecord rec = new InboundRecord();
        rec.setOrderId(o.getId());
        rec.setInspectorId(14L);
        rec.setOperatorCanteenId(o.getCanteenId());
        rec.setOrderQuantity(orderQty);
        rec.setReceiveQuantity(receive);
        rec.setLossQuantity(loss);
        rec.setLossRate(lossRate);
        rec.setIsPartial(loss.signum() > 0 ? 1 : 0);
        rec.setQualityResult(dto.getQualityResult() == null ? "合格" : dto.getQualityResult());
        rec.setOperatorName(UserContext.get().getRealName());
        rec.setRemark(dto.getRemark());
        rec.setInboundTime(LocalDateTime.now());
        inboundMapper.insert(rec);

        // 订单明细回填实收/损耗
        OrderItem item = itemMapper.selectOne(new LambdaQueryWrapper<OrderItem>()
                .eq(OrderItem::getOrderId, o.getId()).last("LIMIT 1"));
        if (item != null) {
            item.setActualQuantity(receive);
            item.setLossQuantity(loss);
            itemMapper.updateById(item);
        }

        // 订单 60 -> 70 -> 80
        Orders fresh = ordersMapper.selectById(o.getId());
        orderService.changeStatus(o.getId(), 70, fresh.getVersion());
        fresh = ordersMapper.selectById(o.getId());
        orderService.changeStatus(o.getId(), 80, fresh.getVersion());

        // 生成结算单（待审核）
        long count = settlementMapper.selectCount(null);
        Settlement st = new Settlement();
        String sp = "JS" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        st.setSettlementNo(com.canteen.common.Codes.next(sp, 3, (int) (count % 800 + 100),
                code -> settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>().eq(Settlement::getSettlementNo, code)) > 0));
        st.setOrderId(o.getId());
        st.setFarmerId(o.getFarmerId());
        st.setCanteenId(o.getCanteenId());
        st.setTotalAmount(orderAmount);
        st.setLossDeduction(lossDeduction);
        st.setDeduction(deduction);
        st.setActualAmount(actual);
        st.setStatus("待审核");
        st.setRemark("按实收数量自动核算损耗");
        st.setCreateTime(LocalDateTime.now());
        settlementMapper.insert(st);
        insertItem(st.getId(), "订单货款", "ADD", orderAmount,
                orderQty.stripTrailingZeros().toPlainString() + "kg × ¥" + price);
        if (lossDeduction.signum() > 0) {
            insertItem(st.getId(), "生鲜损耗扣款", "SUB", lossDeduction,
                    "损耗 " + loss.stripTrailingZeros().toPlainString() + "kg × ¥" + price);
        }
        if (deduction.signum() > 0) {
            insertItem(st.getId(), "质检扣款", "SUB", deduction, "质检不合格，按约定比例扣款 20%");
        }

        // 损耗阈值 -> 扣减信用 + 生成预警
        BigDecimal threshold = BigDecimal.valueOf(configService.getDouble("warn_loss_rate", 5));
        if (lossRate.compareTo(threshold) >= 0) {
            creditService.punishLoss(o.getFarmerId(), o.getId());
        }
        traceService.appendNode(o.getId());
        logService.record("入库确认", "订单 " + o.getOrderNo() + " 入库 " + receive.stripTrailingZeros().toPlainString()
                + "kg，损耗率 " + lossRate + "%，实际结算 ¥" + actual);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("settlementNo", st.getSettlementNo());
        res.put("orderAmount", orderAmount);
        res.put("lossQuantity", loss);
        res.put("lossRate", lossRate);
        res.put("lossDeduction", lossDeduction);
        res.put("actualAmount", actual);
        res.put("lossAlarm", lossRate.compareTo(threshold) >= 0);
        res.put("threshold", threshold);
        res.put("orderStatus", "待结算");
        return res;
    }

    private void insertItem(Long settlementId, String name, String type, BigDecimal amount, String remark) {
        SettlementItem si = new SettlementItem();
        si.setSettlementId(settlementId);
        si.setItemName(name);
        si.setItemType(type);
        si.setAmount(amount);
        si.setRemark(remark);
        settlementItemMapper.insert(si);
    }

    /** 近期入库记录 */
    public List<Map<String, Object>> recent(int limit) {
        Long canteenId = UserContext.getOrNull() == null ? null : UserContext.getOrNull().getCanteenId();
        LambdaQueryWrapper<InboundRecord> qw = new LambdaQueryWrapper<InboundRecord>()
                .orderByDesc(InboundRecord::getId).last("LIMIT " + limit);
        if (canteenId != null && !UserContext.isAdmin()) {
            qw.eq(InboundRecord::getOperatorCanteenId, canteenId);
        }
        List<InboundRecord> list = inboundMapper.selectList(qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (InboundRecord r : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            Orders o = ordersMapper.selectById(r.getOrderId());
            Product p = o == null ? null : productMapper.selectById(o.getProductId());
            m.put("orderNo", o == null ? "-" : o.getOrderNo());
            m.put("productName", p == null ? "-" : p.getName());
            m.put("orderQuantity", r.getOrderQuantity());
            m.put("receiveQuantity", r.getReceiveQuantity());
            m.put("lossQuantity", r.getLossQuantity());
            m.put("lossRate", r.getLossRate());
            m.put("unit", p == null ? "kg" : p.getUnit());
            Settlement st = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                    .eq(Settlement::getOrderId, r.getOrderId()).last("LIMIT 1"));
            m.put("actualAmount", st == null ? BigDecimal.ZERO : st.getActualAmount());
            m.put("qualityResult", r.getQualityResult());
            m.put("operatorName", r.getOperatorName());
            m.put("inboundTime", r.getInboundTime());
            rows.add(m);
        }
        return rows;
    }
}

