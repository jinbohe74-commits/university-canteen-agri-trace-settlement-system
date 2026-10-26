package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
import com.canteen.common.UserContext;
import com.canteen.dto.QualitySubmitDTO;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.OrderVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 质检录入：抽检项目、合格判定、批次与二维码生成
 */
@Service
@RequiredArgsConstructor
public class QualityService {

    private final OrdersMapper ordersMapper;
    private final QualityReportMapper qualityMapper;
    private final DeliveryOrderMapper deliveryMapper;
    private final TraceRecordMapper traceMapper;
    private final RefService refService;
    private final OrderService orderService;
    private final TraceService traceService;
    private final CreditService creditService;
    private final LogService logService;
    private final StatMapper statMapper;
    private final ObjectMapper json = new ObjectMapper();

    public static final List<Map<String, Object>> STANDARDS = List.of(
            Map.of("name", "农残限量", "value", "≤0.05", "unit", "mg/kg"),
            Map.of("name", "叶菜含水率", "value", "≥95", "unit", "%"),
            Map.of("name", "冷链温度", "value", "2~8", "unit", "℃"),
            Map.of("name", "腐烂率", "value", "≤1", "unit", "%")
    );

    /** 质检任务列表 + 统计 */
    public Map<String, Object> tasks(int page, int size) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Orders> pending = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getStatus, 30).orderByAsc(Orders::getCreateTime));
        List<OrderVO> vos = refService.toOrderVO(pending);
        int from = Math.min((page - 1) * size, vos.size());
        int to = Math.min(from + size, vos.size());
        m.put("records", vos.subList(from, to));
        m.put("total", vos.size());
        m.put("page", page);
        m.put("size", size);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", pending.size());
        stats.put("todayInspected", statMapper.todayInspected());
        stats.put("todayQualified", statMapper.todayQualified());
        stats.put("delivering", ordersMapper.selectCount(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getStatus, 50)));
        stats.put("unqualified", statMapper.unqualifiedTotal());
        stats.put("passRate", statMapper.qualityPassRate());
        m.put("stats", stats);

        // 今日全部任务（含已完成）
        List<Orders> todayAll = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .ge(Orders::getCreateTime, LocalDate.now().atStartOfDay())
                .in(Orders::getStatus, List.of(30, 40, 41, 50, 60, 70, 80, 90, 100))
                .orderByDesc(Orders::getCreateTime).last("LIMIT 20"));
        m.put("todayTasks", refService.toOrderVO(todayAll));
        m.put("trend", statMapper.inspectorPassTrend());
        return m;
    }

    /** 质检录入页数据：批次、样品、检测项模板 */
    public Map<String, Object> detail(Long orderId) {
        Orders o = orderService.getById(orderId);
        if (o.getStatus() != 30 && o.getStatus() != 40 && o.getStatus() != 41) {
            throw new BizException("仅待质检 / 已质检订单可录入质检结果");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        String batchNo = o.getBatchNo();
        if (batchNo == null || batchNo.isBlank()) {
            batchNo = traceService.generateBatchNo(o.getFarmerId(), o.getProductId());
        }
        m.put("order", refService.toOrderVO(o));
        m.put("batchNo", batchNo);
        m.put("sampleQty", o.getQuantity().multiply(new java.math.BigDecimal("0.005"))
                .setScale(2, java.math.RoundingMode.HALF_UP).max(new java.math.BigDecimal("0.5")));
        m.put("items", defaultItems());
        m.put("standards", STANDARDS);
        m.put("report", qualityMapper.selectOne(new LambdaQueryWrapper<QualityReport>()
                .eq(QualityReport::getOrderId, orderId).orderByDesc(QualityReport::getId).last("LIMIT 1")));
        return m;
    }

    private List<Map<String, Object>> defaultItems() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item("农药残留", "有机磷 / 氨基甲酸酯快检", "检测值 0.02 mg/kg（限 0.05）", "0.02", "mg/kg", true));
        items.add(item("重金属", "铅 / 镉抽检", "未检出", "未检出", "", true));
        items.add(item("含水率", "叶菜类标准 ≥95%", "实测 92%", "92", "%", true));
        items.add(item("新鲜度", "外观 / 色泽 / 气味感官", "叶片挺括、无腐烂", "良好", "", true));
        items.add(item("重量复核", "复秤与订单核对", "与订单相符", "相符", "", true));
        items.add(item("冷链温度", "到货中心温度 2~8℃", "实测 6.0℃", "6.0", "℃", true));
        return items;
    }

    private Map<String, Object> item(String name, String standard, String value, String v, String unit, boolean ok) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("standard", standard);
        m.put("value", value);
        m.put("input", v);
        m.put("unit", unit);
        m.put("ok", ok);
        return m;
    }

    /** 提交质检结果 */
    @Transactional
    public Map<String, Object> submit(QualitySubmitDTO dto) {
        Orders o = orderService.getById(dto.getOrderId());
        if (o.getStatus() != 30) {
            throw new BizException("订单当前状态为【" + RefService.statusName(o.getStatus()) + "】，无需质检");
        }
        String batchNo = dto.getBatchNo();
        if (batchNo == null || batchNo.isBlank()) {
            batchNo = traceService.generateBatchNo(o.getFarmerId(), o.getProductId());
        }
        boolean pass = "合格".equals(dto.getResult());
        List<Map<String, Object>> items = dto.getItems() == null || dto.getItems().isEmpty()
                ? defaultItems() : dto.getItems();
        if (!pass) {
            boolean anyBad = items.stream().anyMatch(i -> Boolean.FALSE.equals(i.get("ok")));
            if (!anyBad && !items.isEmpty()) {
                items.get(0).put("ok", false);
            }
        }
        QualityReport qr = new QualityReport();
        qr.setOrderId(o.getId());
        qr.setBatchNo(batchNo);
        qr.setInspectorId(UserContext.userId());
        qr.setInspector(UserContext.get().getRealName());
        qr.setSampleQty(dto.getSampleQty() == null ? new java.math.BigDecimal("2") : dto.getSampleQty());
        qr.setResult(dto.getResult());
        qr.setItems(writeJson(items));
        qr.setStandards(writeJson(STANDARDS));
        qr.setReportFile(dto.getReportFile());
        qr.setRemark(dto.getRemark());
        qr.setInspectTime(LocalDateTime.now());
        qr.setHash(TraceService.sha256(batchNo + "|" + o.getFarmerId() + "|" + dto.getResult()));
        qualityMapper.insert(qr);

        Map<String, Object> res = new LinkedHashMap<>();
        if (pass) {
            orderService.bindBatch(o.getId(), batchNo);
            orderService.changeStatus(o.getId(), 40, o.getVersion());
            TraceRecord t = traceService.create(ordersMapper.selectById(o.getId()), qr);
            traceService.appendNode(o.getId());
            res.put("qrCode", t.getQrCode());
            res.put("hash", t.getHash());
            res.put("batchNo", batchNo);
            logService.record("质检配送", "录入质检报告 " + batchNo + "（合格），生成溯源二维码");
        } else {
            orderService.changeStatus(o.getId(), 41, o.getVersion());
            creditService.punishQuality(o.getFarmerId(), o.getId());
            Warning w = new Warning();
            w.setWarningNo("YJ" + java.time.LocalDate.now().toString().replace("-", "")
                    + String.format("%03d", new Random().nextInt(900) + 100));
            w.setRiskLevel("高");
            w.setWarningType("质检预警");
            w.setTarget("批次 " + batchNo);
            w.setContent((o.getOrderNo()) + " " + qr.getInspector() + " 质检不合格，整批驳回并扣减信用 -5");
            w.setStatus("待处理");
            w.setTriggerTime(LocalDateTime.now());
            warningMapper.insert(w);
            res.put("batchNo", batchNo);
            logService.record("质检配送", "录入质检报告 " + batchNo + "（不合格），已驳回并扣减信用", "失败");
        }
        res.put("result", dto.getResult());
        res.put("orderStatus", RefService.statusName(ordersMapper.selectById(o.getId()).getStatus()));
        return res;
    }

    private final WarningMapper warningMapper;

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
