package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
import com.canteen.common.LoginUser;
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
 *
 * <p>修复：P1-3 归属校验、P1-5 单价校验、P1-6 质检扣款改由质检报告驱动且比例可配置、
 * P3 质检员 ID 不再硬编码 / 损耗阈值改为「严格超过」。</p>
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
    private final DeliveryOrderMapper deliveryMapper;
    private final ProductMapper productMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final RefService refService;
    private final OrderService orderService;
    private final CreditService creditService;
    private final ConfigService configService;
    private final TraceService traceService;
    private final LogService logService;
    private final NotificationService notificationService;
    private final StockService stockService;

    /** 入库确认页数据（订单 + 批次 + 结算预览） */
    public Map<String, Object> preview(String orderNo) {
        LoginUser lu = UserContext.get();
        Orders o;
        LambdaQueryWrapper<Orders> qw = new LambdaQueryWrapper<>();
        if (orderNo == null || orderNo.isBlank()) {
            // 修复 P2：绑定食堂为空时不再硬编码 1L 兜底，直接拒绝，避免越权读到他人数据
            Long canteenId = lu.getCanteenId();
            if (canteenId == null && !"ADMIN".equals(lu.getRole())) {
                throw new BizException(403, "当前账号未绑定食堂档案");
            }
            qw.eq(Orders::getStatus, 60);
            if (canteenId != null && !"ADMIN".equals(lu.getRole())) {
                qw.eq(Orders::getCanteenId, canteenId);
            }
            qw.orderByAsc(Orders::getCreateTime).last("LIMIT 1");
        } else {
            qw.and(w -> w.eq(Orders::getOrderNo, orderNo).or().eq(Orders::getBatchNo, orderNo))
                    .last("LIMIT 1");
        }
        o = ordersMapper.selectOne(qw);
        if (o == null) {
            throw new BizException(404, "未找到待入库订单，请检查订单号或批次号");
        }
        assertCanteenScope(o, lu);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", refService.toOrderVO(o));
        QualityReport qr = latestQualityReport(o.getId());
        m.put("quality", qr);
        m.put("batchNo", o.getBatchNo() == null ? (qr == null ? "" : qr.getBatchNo()) : o.getBatchNo());
        BigDecimal lossRateThreshold = BigDecimal.valueOf(configService.getDouble("warn_loss_rate", 5));
        m.put("lossRateThreshold", lossRateThreshold);
        m.put("canInbound", o.getStatus() == 60);
        m.put("receiveQuantity", o.getQuantity());
        m.put("lossQuantity", BigDecimal.ZERO);
        m.put("lossRate", BigDecimal.ZERO);
        m.put("orderAmount", o.getTotalAmount());
        m.put("actualAmount", o.getTotalAmount());
        m.put("lossDeduction", BigDecimal.ZERO);
        m.put("recent", recent(5));
        return m;
    }

    private QualityReport latestQualityReport(Long orderId) {
        return qualityMapper.selectOne(new LambdaQueryWrapper<QualityReport>()
                .eq(QualityReport::getOrderId, orderId)
                .orderByDesc(QualityReport::getId).last("LIMIT 1"));
    }

    /** 修复 P1-3：带 id 的入库接口必须校验订单归属，杜绝跨食堂越权。 */
    private void assertCanteenScope(Orders o, LoginUser lu) {
        if (lu == null || "ADMIN".equals(lu.getRole())) {
            return;
        }
        Long canteenId = lu.getCanteenId();
        if (canteenId == null || !Objects.equals(o.getCanteenId(), canteenId)) {
            throw new BizException(403, "无权操作其他食堂的订单");
        }
    }

    /** 入库确认：生成入库记录 + 结算单，订单流转至待结算 */
    @Transactional
    public Map<String, Object> confirm(InboundDTO dto) {
        Orders o = ordersMapper.selectById(dto.getOrderId());
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        assertCanteenScope(o, UserContext.get());
        if (o.getStatus() != 60) {
            throw new BizException("仅【已送达】订单可入库，当前状态：" + RefService.statusName(o.getStatus()));
        }
        // 修复 P1-12：审核驳回后的结算单允许复用重算，其余状态一律拒绝重复入库
        Settlement existing = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getOrderId, o.getId()).last("LIMIT 1"));
        if (existing != null && !"已驳回".equals(existing.getStatus())) {
            throw new BizException(409, "该订单已生成结算单，请勿重复入库");
        }
        BigDecimal orderQty = o.getQuantity();
        BigDecimal receive = dto.getReceiveQuantity();
        if (receive == null || receive.signum() <= 0) {
            throw new BizException("实收数量必须大于 0");
        }
        if (receive.compareTo(orderQty) > 0) {
            throw new BizException("实收数量不能大于下单数量（支持部分收货，损耗数量自动核算）");
        }
        BigDecimal price = o.getUnitPrice();
        // 修复 P1-5：负/零单价不得进入金额计算
        if (price == null || price.signum() <= 0) {
            throw new BizException(400, "订单单价必须大于 0，无法结算");
        }
        BigDecimal loss = orderQty.subtract(receive).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lossRate = orderQty.signum() == 0 ? BigDecimal.ZERO
                : loss.multiply(BigDecimal.valueOf(100)).divide(orderQty, 2, RoundingMode.HALF_UP);
        // 有损耗必须说明原因：这条会写进结算单，后续对账、追责全靠它。
        // 原来 remark 可留空，损耗就成了一个没有解释的数字。
        String remark = dto.getRemark() == null ? "" : dto.getRemark().trim();
        if (loss.signum() > 0 && remark.length() < 5) {
            throw new BizException("存在损耗 " + loss + "kg，请填写不少于 5 字的损耗原因（会写入结算单）");
        }
        BigDecimal orderAmount = orderQty.multiply(price).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lossDeduction = loss.multiply(price).setScale(2, RoundingMode.HALF_UP);

        // 修复 P1-6：质检结论只认库里的质检报告，扣款比例来自系统参数，不再信任前端入参
        QualityReport qr = latestQualityReport(o.getId());
        boolean unqualified = qr != null && "不合格".equals(qr.getResult());
        BigDecimal deduction = BigDecimal.ZERO;
        BigDecimal deductionRate = BigDecimal.valueOf(configService.getDouble("quality_deduction_rate", 0.2));
        if (deductionRate.signum() < 0 || deductionRate.compareTo(BigDecimal.ONE) > 0) {
            deductionRate = new BigDecimal("0.2");
        }
        if (unqualified) {
            deduction = orderAmount.multiply(deductionRate).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal actual = orderAmount.subtract(lossDeduction).subtract(deduction)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        InboundRecord rec = new InboundRecord();
        rec.setOrderId(o.getId());
        // 修复 P3：质检员 ID 取质检报告，缺失时留空，不再硬编码 14
        rec.setInspectorId(qr == null ? null : qr.getInspectorId());
        rec.setOperatorCanteenId(o.getCanteenId());
        rec.setOrderQuantity(orderQty);
        rec.setReceiveQuantity(receive);
        rec.setLossQuantity(loss);
        rec.setLossRate(lossRate);
        rec.setIsPartial(loss.signum() > 0 ? 1 : 0);
        rec.setQualityResult(qr == null || qr.getResult() == null ? "合格" : qr.getResult());
        rec.setOperatorName(UserContext.get().getRealName());
        rec.setRemark(remark);
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

        // 生成或复用结算单（待审核）；驳回重算时复用原记录，规避 uk_settlement_order 唯一键冲突
        Settlement st = existing == null ? new Settlement() : existing;
        if (existing == null) {
            long count = settlementMapper.selectCount(null);
            String sp = "JS" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            st.setSettlementNo(com.canteen.common.Codes.next(sp, 3, (int) (count % 800 + 100),
                    code -> settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                            .eq(Settlement::getSettlementNo, code)) > 0));
            st.setCreateTime(LocalDateTime.now());
        } else {
            settlementItemMapper.delete(new LambdaQueryWrapper<SettlementItem>()
                    .eq(SettlementItem::getSettlementId, st.getId()));
            st.setAuditor(null);
            st.setConfirmTime(null);
        }
        st.setOrderId(o.getId());
        st.setFarmerId(o.getFarmerId());
        st.setCanteenId(o.getCanteenId());
        st.setTotalAmount(orderAmount);
        st.setLossDeduction(lossDeduction);
        st.setDeduction(deduction);
        st.setActualAmount(actual);
        st.setStatus("待审核");
        st.setRemark(existing == null ? "按实收数量自动核算损耗" : "驳回后重新核算（原单号复用）");
        if (existing == null) {
            settlementMapper.insert(st);
        } else {
            settlementMapper.updateById(st);
        }
        insertItem(st.getId(), "订单货款", "ADD", orderAmount,
                orderQty.stripTrailingZeros().toPlainString() + "kg × ¥" + price);
        if (lossDeduction.signum() > 0) {
            insertItem(st.getId(), "生鲜损耗扣款", "SUB", lossDeduction,
                    "损耗 " + loss.stripTrailingZeros().toPlainString() + "kg × ¥" + price);
        }
        if (deduction.signum() > 0) {
            insertItem(st.getId(), "质检扣款", "SUB", deduction,
                    "依据质检报告判定不合格，按系统参数扣款 " +
                            deductionRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
        }

        // 修复 P3：README 口径为「超过阈值」才扣分，等于阈值不扣
        BigDecimal threshold = BigDecimal.valueOf(configService.getDouble("warn_loss_rate", 5));
        boolean lossAlarm = lossRate.compareTo(threshold) > 0;
        if (lossAlarm) {
            creditService.punishLoss(o.getFarmerId(), o.getId());
        }
        traceService.appendNode(o.getId());
        // 入库即入库存：库存只由真实的入库/领用流水驱动
        stockService.addStock(o.getCanteenId(), o.getProductId(), receive);
        logService.record("入库确认", "订单 " + o.getOrderNo() + " 入库 " + receive.stripTrailingZeros().toPlainString()
                + "kg，损耗率 " + lossRate + "%，实际结算 ¥" + actual);
        notificationService.toOrderParties(o.getId(), "INBOUND", o.getOrderNo(),
                "已入库，待结算", "订单 " + o.getOrderNo() + " 实收 " + receive.stripTrailingZeros().toPlainString()
                        + "kg，损耗 " + loss.stripTrailingZeros().toPlainString() + "kg，实际结算 ¥" + actual
                        + "，结算单 " + st.getSettlementNo() + " 待审核",
                Map.of("FARMER", "/farmer/settlement", "ADMIN", "/admin/settlements"));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("settlementNo", st.getSettlementNo());
        res.put("orderAmount", orderAmount);
        res.put("lossQuantity", loss);
        res.put("lossRate", lossRate);
        res.put("lossDeduction", lossDeduction);
        res.put("actualAmount", actual);
        res.put("lossAlarm", lossAlarm);
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
    /**
     * 待入库订单清单（本食堂全部「已送达」订单）。
     *
     * <p>用户反馈「入库确认不显示所有待处理订单，要处理完一个才能看下一个」——原来的 preview()
     * 只会自动带出最近一笔，处理完就清空，采购员必须自己记住下一笔单号再手输，等于把
     * 「今天还有哪些货要收」这件事交给人的记忆。这里把待收清单一次性查出来，
     * 口径与 preview 的自动取单完全同源（status=60 + 本食堂），保证点进去就是同一批单。</p>
     */
    public Map<String, Object> pending(int limit) {
        LoginUser lu = UserContext.get();
        Long canteenId = lu == null ? null : lu.getCanteenId();
        boolean admin = lu != null && "ADMIN".equals(lu.getRole());
        if (canteenId == null && !admin) {
            throw new BizException(403, "当前账号未绑定食堂档案");
        }
        LambdaQueryWrapper<Orders> qw = new LambdaQueryWrapper<Orders>()
                .eq(Orders::getStatus, 60);
        if (!admin && canteenId != null) {
            qw.eq(Orders::getCanteenId, canteenId);
        }
        qw.orderByAsc(Orders::getCreateTime).last("LIMIT " + Math.max(1, Math.min(limit, 100)));
        List<Orders> list = ordersMapper.selectList(qw);
        double threshold = configService.getDouble("warn_loss_rate", 5);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Orders o : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            Product p = o.getProductId() == null ? null : productMapper.selectById(o.getProductId());
            Farmer f = o.getFarmerId() == null ? null : farmerMapper.selectById(o.getFarmerId());
            QualityReport qr = latestQualityReport(o.getId());
            DeliveryOrder d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                    .eq(DeliveryOrder::getOrderId, o.getId())
                    .orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));

            m.put("orderId", o.getId());
            m.put("orderNo", o.getOrderNo());
            m.put("batchNo", o.getBatchNo() == null ? (qr == null ? "" : qr.getBatchNo()) : o.getBatchNo());
            m.put("productName", p == null ? "-" : p.getName());
            m.put("categoryId", p == null ? null : p.getCategoryId());
            m.put("unit", p == null ? "kg" : p.getUnit());
            m.put("farmerName", f == null ? "-" : f.getName());
            m.put("quantity", o.getQuantity());
            m.put("unitPrice", o.getUnitPrice());
            m.put("totalAmount", o.getTotalAmount());
            m.put("expectedDelivery", o.getExpectedDelivery());
            // 真实送达时间优先取配送单到达时间；没有配送单就退回订单实际送达字段
            LocalDateTime arrive = d == null ? null : d.getArriveTime();
            m.put("arriveTime", arrive != null ? arrive : o.getActualDelivery());
            m.put("qualityResult", qr == null ? null : qr.getResult());
            m.put("temperature", d == null ? null : d.getTemperature());
            m.put("lossRateThreshold", BigDecimal.valueOf(threshold));
            // 冷链超温 / 质检不合格：收这批之前必须知道的异常，列表上直接标出来
            boolean coldBad = d != null && d.getTemperature() != null
                    && (d.getTemperature().doubleValue() < 2 || d.getTemperature().doubleValue() > 8);
            boolean qcBad = qr != null && qr.getResult() != null && !qr.getResult().contains("合格");
            m.put("coldAbnormal", coldBad);
            m.put("qualityAbnormal", qcBad);
            rows.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", rows.size());
        res.put("lossRateThreshold", BigDecimal.valueOf(threshold));
        res.put("items", rows);
        return res;
    }

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
