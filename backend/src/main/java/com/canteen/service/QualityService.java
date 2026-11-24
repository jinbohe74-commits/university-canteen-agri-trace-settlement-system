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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 质检录入：按品类取检测项、按限值自动判定、生成批次与二维码。
 *
 * <p><b>2026-11-10 改造</b>：原实现把 6 条检测项写死在代码里（农药残留 0.02 / 含水率 92%），
 * 检白萝卜、马铃薯、香菇都用同一套值；前端也没渲染实测值输入框，所以质检员只要点两下
 * 「达标 / 不合格」再选结论就能过 —— 用户反馈「随便点点就行，感觉不到和实际有关」。
 * 现在检测项来自 {@code quality_standard} 表（按一级品类分组），实测值必须逐项录入，
 * 结论由后端按限值判定，前端传来的结论不一致会被直接拒绝。</p>
 */
@Service
@RequiredArgsConstructor
public class QualityService {

    private final OrdersMapper ordersMapper;
    private final QualityReportMapper qualityMapper;
    private final QualityStandardMapper standardMapper;
    private final DeliveryOrderMapper deliveryMapper;
    private final TraceRecordMapper traceMapper;
    private final RefService refService;
    private final OrderService orderService;
    private final TraceService traceService;
    private final CreditService creditService;
    private final DeliveryService deliveryService;
    private final LogService logService;
    private final NotificationService notificationService;
    private final StatMapper statMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final ObjectMapper json = new ObjectMapper();

    /** 没有匹配到品类标准时的兜底品类（叶菜类），并会在返回里标明「用的是通用标准」 */
    private static final Long FALLBACK_CATEGORY = 1L;

    /** 不合规备注的最少字数：一句话都没有就驳回，等于没写原因 */
    private static final int MIN_REMARK_ON_FAIL = 10;

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

        List<Orders> todayAll = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .ge(Orders::getCreateTime, LocalDate.now().atStartOfDay())
                .in(Orders::getStatus, List.of(30, 40, 41, 50, 60, 70, 80, 90, 100))
                .orderByDesc(Orders::getCreateTime).last("LIMIT 20"));
        m.put("todayTasks", refService.toOrderVO(todayAll));
        m.put("trend", statMapper.inspectorPassTrend());
        return m;
    }

    /** 质检录入页数据：批次、样品、按品类匹配的检测项模板 */
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

        Long topCat = topCategoryId(o.getProductId());
        List<QualityStandard> stds = standardsOf(topCat);
        String catName = categoryName(topCat);
        m.put("categoryId", topCat);
        m.put("categoryName", catName);
        m.put("standardFallback", stds.isEmpty());
        m.put("standards", stds);
        m.put("items", stds.stream().map(this::toTemplate).toList());

        // 抽检量按品类规格给建议值，但不允许超过本单数量
        BigDecimal spec = stds.stream().map(QualityStandard::getSampleSpec)
                .filter(Objects::nonNull).findFirst().orElse(new BigDecimal("0.50"));
        BigDecimal suggested = spec.min(o.getQuantity());
        m.put("sampleSpec", spec);
        m.put("sampleQty", suggested);
        m.put("maxSampleQty", o.getQuantity());
        m.put("report", qualityMapper.selectOne(new LambdaQueryWrapper<QualityReport>()
                .eq(QualityReport::getOrderId, orderId).orderByDesc(QualityReport::getId).last("LIMIT 1")));
        return m;
    }

    /** 商品档案挂二级品类（白萝卜），质检标准挂一级品类（根茎类），这里做回溯 */
    private Long topCategoryId(Long productId) {
        Product p = productMapper.selectById(productId);
        if (p == null || p.getCategoryId() == null) {
            return null;
        }
        ProductCategory c = categoryMapper.selectById(p.getCategoryId());
        int guard = 0;
        while (c != null && c.getParentId() != null && c.getParentId() != 0 && guard++ < 8) {
            c = categoryMapper.selectById(c.getParentId());
        }
        return c == null ? null : c.getId();
    }

    private String categoryName(Long categoryId) {
        if (categoryId == null) {
            return "";
        }
        ProductCategory c = categoryMapper.selectById(categoryId);
        return c == null ? "" : c.getCategoryName();
    }

    /**
     * 取该品类的检测项。品类没有配标准时退回叶菜类通用项，
     * 但绝不允许「没标准就直接判合格」——那等于放行。
     */
    private List<QualityStandard> standardsOf(Long topCategoryId) {
        if (topCategoryId != null) {
            List<QualityStandard> list = standardMapper.listByCategory(topCategoryId);
            if (!list.isEmpty()) {
                return list;
            }
        }
        return standardMapper.listByCategory(FALLBACK_CATEGORY);
    }

    /** 模板：不带实测值，等质检员填 */
    private Map<String, Object> toTemplate(QualityStandard s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemName", s.getItemName());
        m.put("method", s.getMethod());
        m.put("unit", s.getUnit());
        m.put("minValue", s.getMinValue());
        m.put("maxValue", s.getMaxValue());
        m.put("judgeType", s.getJudgeType());
        m.put("required", s.getRequired() != null && s.getRequired() == 1);
        m.put("input", "");
        m.put("note", "");
        m.put("ok", null);
        return m;
    }

    /** 提交质检结果：先按标准判定，再决定是否放行 */
    @Transactional
    public Map<String, Object> submit(QualitySubmitDTO dto) {
        Orders o = orderService.getById(dto.getOrderId());
        if (o.getStatus() != 30) {
            throw new BizException("订单当前状态为【" + RefService.statusName(o.getStatus()) + "】，无需质检");
        }
        List<QualityStandard> stds = standardsOf(topCategoryId(o.getProductId()));
        if (stds.isEmpty()) {
            throw new BizException("该品类未配置质检标准，请联系管理员在质检标准中补充后再录入");
        }

        BigDecimal orderQty = o.getQuantity();
        BigDecimal sampleQty = dto.getSampleQty() == null ? new BigDecimal("0.5") : dto.getSampleQty();
        if (sampleQty.compareTo(orderQty) > 0) {
            throw new BizException("抽检数量不能大于本单数量（" + orderQty + " kg）");
        }

        // 前端提交的实测值按检测项名归类
        Map<String, QualitySubmitDTO.Item> given = new LinkedHashMap<>();
        if (dto.getItems() != null) {
            for (QualitySubmitDTO.Item it : dto.getItems()) {
                if (it != null && it.getItemName() != null) {
                    given.put(it.getItemName().trim(), it);
                }
            }
        }

        List<Map<String, Object>> judged = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (QualityStandard s : stds) {
            QualitySubmitDTO.Item it = given.get(s.getItemName());
            String input = it == null || it.getInput() == null ? "" : it.getInput().trim();
            boolean required = s.getRequired() != null && s.getRequired() == 1;
            if (input.isEmpty()) {
                if (required) {
                    missing.add(s.getItemName());
                }
                continue;
            }
            Verdict v = judge(s, input);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemName", s.getItemName());
            row.put("method", s.getMethod());
            row.put("unit", s.getUnit());
            row.put("minValue", s.getMinValue());
            row.put("maxValue", s.getMaxValue());
            row.put("judgeType", s.getJudgeType());
            row.put("input", input);
            row.put("note", it == null ? "" : it.getNote());
            row.put("ok", v.ok);
            row.put("verdict", v.text);
            judged.add(row);
            if (!v.ok) {
                failed.add(s.getItemName() + "（" + input + (s.getUnit() == null ? "" : s.getUnit())
                        + "，标准 " + limitText(s) + "）");
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException("以下必检项还没有录入实测值：" + String.join("、", missing));
        }
        if (judged.isEmpty()) {
            throw new BizException("请至少录入一项检测结果");
        }

        String autoResult = failed.isEmpty() ? "合格" : "不合格";
        // 结论不允许与实测数据矛盾：这是原来最容易被「随便点点」绕过去的地方
        if (dto.getResult() != null && !dto.getResult().isBlank()
                && !autoResult.equals(dto.getResult())) {
            throw new BizException("检测数据判定为【" + autoResult + "】，与提交结论【" + dto.getResult()
                    + "】不一致" + (failed.isEmpty() ? "" : "：" + String.join("；", failed)));
        }

        List<String> photos = dto.getPhotos() == null ? List.of()
                : dto.getPhotos().stream().filter(x -> x != null && !x.isBlank()).toList();
        String remark = dto.getRemark() == null ? "" : dto.getRemark().trim();
        boolean pass = "合格".equals(autoResult);
        if (!pass) {
            if (remark.length() < MIN_REMARK_ON_FAIL) {
                throw new BizException("判定不合格必须填写不少于 " + MIN_REMARK_ON_FAIL
                        + " 字的处置说明（问题描述、处理方式）");
            }
            if (photos.isEmpty()) {
                throw new BizException("判定不合格必须至少上传一张现场照片作为存证");
            }
        }

        String batchNo = dto.getBatchNo();
        if (batchNo == null || batchNo.isBlank()) {
            batchNo = traceService.generateBatchNo(o.getFarmerId(), o.getProductId());
        }

        QualityReport qr = new QualityReport();
        qr.setOrderId(o.getId());
        qr.setBatchNo(batchNo);
        qr.setInspectorId(UserContext.userId());
        qr.setInspector(UserContext.get().getRealName());
        qr.setSampleQty(sampleQty);
        qr.setResult(autoResult);
        qr.setItems(writeJson(judged));
        qr.setStandards(writeJson(stds));
        qr.setReportFile(photos.isEmpty() ? dto.getReportFile()
                : String.join(",", photos));
        qr.setRemark(remark);
        qr.setInspectTime(LocalDateTime.now());
        qr.setHash(TraceService.sha256(batchNo + "|" + o.getFarmerId() + "|" + autoResult));
        qualityMapper.insert(qr);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", judged);
        res.put("failCount", failed.size());
        if (pass) {
            orderService.bindBatch(o.getId(), batchNo);
            orderService.changeStatus(o.getId(), 40, o.getVersion());
            deliveryService.createFor(ordersMapper.selectById(o.getId()));
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
            String wp = "YJ" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
            w.setWarningNo(com.canteen.common.Codes.next(wp, 3, 100,
                    code -> warningMapper.selectCount(new LambdaQueryWrapper<Warning>()
                            .eq(Warning::getWarningNo, code)) > 0));
            w.setRiskLevel("高");
            w.setWarningType("质检预警");
            w.setTarget("批次 " + batchNo);
            w.setContent(o.getOrderNo() + " " + qr.getInspector() + " 质检不合格（"
                    + String.join("、", failed.stream().map(x -> x.split("（")[0]).toList())
                    + "），整批驳回并扣减信用 -5");
            w.setStatus("待处理");
            w.setTriggerTime(LocalDateTime.now());
            warningMapper.insert(w);
            res.put("batchNo", batchNo);
            res.put("failedItems", failed);
            logService.record("质检配送", "录入质检报告 " + batchNo + "（不合格），已驳回并扣减信用", "失败");
        }
        notificationService.toOrderParties(o.getId(), "QUALITY", o.getOrderNo(),
                "质检" + autoResult,
                "订单 " + o.getOrderNo() + " 批次 " + batchNo + " 质检" + autoResult
                        + (failed.isEmpty() ? "，已放行配送"
                        : "：" + String.join("、", failed.stream().map(x -> x.split("（")[0]).toList()) + "，整批驳回"),
                "合格".equals(autoResult)
                        ? Map.of("FARMER", "/farmer/orders", "CANTEEN", "/purchaser/orders")
                        : Map.of("FARMER", "/farmer/orders", "ADMIN", "/admin/warnings"));
        res.put("result", autoResult);
        res.put("orderStatus", RefService.statusName(ordersMapper.selectById(o.getId()).getStatus()));
        return res;
    }

    /** 单项判定结果 */
    private record Verdict(boolean ok, String text) {
    }

    /**
     * 按标准判定单项。
     * <p>数值项解析不出数字就判不合格——不能把「空/错值」默默当过。</p>
     */
    private Verdict judge(QualityStandard s, String input) {
        String type = s.getJudgeType() == null ? "range" : s.getJudgeType();
        switch (type) {
            case "pass" -> {
                String v = input.toLowerCase(Locale.ROOT);
                if (v.equals("合格") || v.equals("达标") || v.equals("是") || v.equals("1") || v.equals("true")) {
                    return new Verdict(true, "感官合格");
                }
                return new Verdict(false, "感官不合格");
            }
            case "equal" -> {
                String v = input.toLowerCase(Locale.ROOT);
                if (v.equals("相符") || v.equals("一致") || v.equals("是") || v.equals("1") || v.equals("true")) {
                    return new Verdict(true, "与随货单相符");
                }
                return new Verdict(false, "与随货单不符");
            }
            default -> {
                // 「未检出 / ND / ＜0.01」是检测报告里的标准写法，等同于低于检出限，判合格。
                // 不能因为解析不出数字就把正常报告判成不合格。
                String lower = input.toLowerCase(Locale.ROOT);
                if (lower.equals("未检出") || lower.equals("nd") || lower.equals("n.d.")
                        || input.startsWith("<") || input.startsWith("＜") || input.startsWith("≤")) {
                    return new Verdict(true, "未检出 / 低于检出限");
                }
                BigDecimal val;
                try {
                    val = new BigDecimal(input.replace(",", "").trim());
                } catch (NumberFormatException e) {
                    return new Verdict(false, "不是有效数值（录入：" + input + "）");
                }
                BigDecimal min = s.getMinValue();
                BigDecimal max = s.getMaxValue();
                if (min != null && val.compareTo(min) < 0) {
                    return new Verdict(false, "低于下限 " + plain(min));
                }
                if (max != null && val.compareTo(max) > 0) {
                    return new Verdict(false, "高于上限 " + plain(max));
                }
                return new Verdict(true, "在 " + limitText(s) + " 范围内");
            }
        }
    }

    private String limitText(QualityStandard s) {
        String unit = s.getUnit() == null ? "" : s.getUnit();
        String type = s.getJudgeType() == null ? "range" : s.getJudgeType();
        if ("pass".equals(type)) {
            return "感官合格";
        }
        if ("equal".equals(type)) {
            return "相符";
        }
        if (s.getMinValue() != null && s.getMaxValue() != null) {
            return plain(s.getMinValue()) + " ~ " + plain(s.getMaxValue()) + unit;
        }
        if (s.getMaxValue() != null) {
            return "≤ " + plain(s.getMaxValue()) + unit;
        }
        if (s.getMinValue() != null) {
            return "≥ " + plain(s.getMinValue()) + unit;
        }
        return "不限";
    }

    private String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
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
