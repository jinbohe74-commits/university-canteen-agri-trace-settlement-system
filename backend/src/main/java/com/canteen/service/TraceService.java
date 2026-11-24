package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.TraceVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 批次溯源：批次码、二维码、SHA-256 存证与全链路节点
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DTF_S = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TraceRecordMapper traceMapper;
    private final OrdersMapper ordersMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final QualityReportMapper qualityMapper;
    private final DeliveryOrderMapper deliveryMapper;
    private final InboundRecordMapper inboundMapper;
    private final SettlementMapper settlementMapper;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${canteen.batch.qr-dir:./uploads/qrcode}")
    private String qrDir;

    /** 批次号规则：PC + 日期 + F + 农户ID + 产品ID + 3 位随机数 */
    public String generateBatchNo(Long farmerId, Long productId) {
        String date = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String seq = "F%04d%04d%03d".formatted(farmerId % 10000, productId % 10000,
                new Random().nextInt(900) + 100);
        String no = "PC" + date + seq;
        int guard = 0;
        while (traceMapper.selectCount(new LambdaQueryWrapper<TraceRecord>().eq(TraceRecord::getBatchNo, no)) > 0
                && guard++ < 5) {
            no = "PC" + date + "F%04d%04d%03d".formatted(farmerId % 10000, productId % 10000,
                    new Random().nextInt(900) + 100);
        }
        return no;
    }

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    /** 生成二维码 PNG，返回可访问 URL */
    public String generateQr(String batchNo) {
        try {
            File dir = new File(qrDir);
            if (!dir.exists() && !dir.mkdirs()) {
                log.warn("二维码目录创建失败: {}", dir.getAbsolutePath());
            }
            File out = new File(dir, batchNo + ".png");
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            BitMatrix matrix = new QRCodeWriter().encode(
                    "http://127.0.0.1:5173/trace?batchNo=" + batchNo, BarcodeFormat.QR_CODE, 320, 320, hints);
            MatrixToImageWriter.writeToPath(matrix, "PNG", Path.of(out.getAbsolutePath()));
            return "/api/files/qrcode/" + batchNo + ".png";
        } catch (Exception e) {
            log.warn("二维码生成失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 把数据库里存的文件路径转换为内联 base64 data URL。
     * 仍从 qrDir 读取真实文件（保留存证文件），但展示不再依赖免鉴权的静态资源接口。
     */
    private String inlineQr(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (stored.startsWith("data:")) {
            return stored;
        }
        try {
            File root = new File(qrDir).getCanonicalFile();
            String name = stored.substring(stored.lastIndexOf('/') + 1);
            File f = new File(root, name).getCanonicalFile();
            if (!f.toPath().startsWith(root.toPath()) || !f.isFile()) {
                log.warn("二维码文件不存在或不合法: {}", stored);
                return null;
            }
            byte[] bytes = Files.readAllBytes(f.toPath());
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.warn("二维码内联失败 {}: {}", stored, e.getMessage());
            return null;
        }
    }

    /**
     * 质检通过后生成溯源记录（哈希 + 二维码 + 链路）
     */
    @Transactional
    public TraceRecord create(Orders order, QualityReport qr) {
        String batchNo = qr.getBatchNo();
        String raw = batchNo + "|" + order.getFarmerId() + "|" + order.getProductId() + "|"
                + qr.getResult() + "|" + order.getQuantity() + "|" + qr.getInspectTime();
        String hash = sha256(raw);
        String qrUrl = generateQr(batchNo);
        TraceRecord t = new TraceRecord();
        t.setBatchNo(batchNo);
        t.setOrderId(order.getId());
        t.setFarmerId(order.getFarmerId());
        t.setProductId(order.getProductId());
        t.setQualityReportId(qr.getId());
        t.setHash(hash);
        t.setQrCode(qrUrl);
        t.setCreateTime(java.time.LocalDateTime.now());
        traceMapper.insert(t);
        return t;
    }

    /** 追加配送/入库节点（刷新链路与哈希） */
    @Transactional
    public void appendNode(Long orderId) {
        TraceRecord t = traceMapper.selectOne(new LambdaQueryWrapper<TraceRecord>()
                .eq(TraceRecord::getOrderId, orderId).orderByDesc(TraceRecord::getId).last("LIMIT 1"));
        if (t == null) {
            return;
        }
        DeliveryOrder d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId).orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));
        InboundRecord ir = inboundMapper.selectOne(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, orderId).orderByDesc(InboundRecord::getId).last("LIMIT 1"));
        if (d != null) {
            t.setDeliveryId(d.getId());
        }
        if (ir != null) {
            t.setInboundId(ir.getId());
        }
        // 关键节点数据变化后重新计算存证哈希
        t.setHash(sha256(t.getBatchNo() + "|" + t.getFarmerId() + "|" + (d == null ? "" : d.getId())
                + "|" + (ir == null ? "" : ir.getReceiveQuantity()) + "|" + t.getCreateTime()));
        t.setChain(buildChainJson(t));
        traceMapper.updateById(t);
    }

    private String buildChainJson(TraceRecord t) {
        try {
            return json.writeValueAsString(buildNodes(t.getBatchNo()));
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 组装溯源查看视图 */
    public TraceVO getTrace(String batchNo) {
        TraceRecord t = traceMapper.selectOne(new LambdaQueryWrapper<TraceRecord>()
                .eq(TraceRecord::getBatchNo, batchNo).last("LIMIT 1"));
        if (t == null) {
            throw new BizException(404, "未找到批次【" + batchNo + "】的溯源信息");
        }
        Orders order = ordersMapper.selectById(t.getOrderId());
        Farmer farmer = farmerMapper.selectById(t.getFarmerId());
        Canteen canteen = order == null ? null : canteenMapper.selectById(order.getCanteenId());
        Product product = productMapper.selectById(t.getProductId());
        TraceVO v = new TraceVO();
        v.setBatchNo(t.getBatchNo());
        v.setHash(t.getHash());
        v.setChainHash(t.getHash());
        // 修复：文件接口已纳入鉴权，<img src="/api/files/..."> 无法携带 Bearer token。
        // 改为把二维码内联为 data URL，既不需要开放免鉴权静态资源，也不改动存量数据。
        v.setQrCode(inlineQr(t.getQrCode()));
        if (product != null) {
            v.setProductName(product.getName());
            v.setProductNo(product.getProductNo());
            v.setUnit(product.getUnit());
            ProductCategory sub = categoryMapper.selectById(product.getCategoryId());
            if (sub != null) {
                ProductCategory big = (sub.getParentId() == null || sub.getParentId() == 0)
                        ? sub : categoryMapper.selectById(sub.getParentId());
                v.setCategoryName(big == null ? sub.getCategoryName() : big.getCategoryName());
            }
        }
        if (farmer != null) {
            v.setFarmerName(farmer.getName());
            v.setFarmerType(farmer.getType());
            v.setFarmerAddress(farmer.getAddress());
            v.setCreditScore(farmer.getCreditScore());
        }
        if (canteen != null) {
            v.setCanteenName(canteen.getName());
            v.setCampus(canteen.getCampus());
        }
        if (order != null) {
            v.setOrderNo(order.getOrderNo());
            v.setOrderQuantity(order.getQuantity());
            v.setUnitPrice(order.getUnitPrice());
            v.setTotalAmount(order.getTotalAmount());
        }
        QualityReport qr = t.getQualityReportId() == null ? null : qualityMapper.selectById(t.getQualityReportId());
        if (qr == null) {
            qr = qualityMapper.selectOne(new LambdaQueryWrapper<QualityReport>()
                    .eq(QualityReport::getOrderId, t.getOrderId()).orderByDesc(QualityReport::getId).last("LIMIT 1"));
        }
        if (qr != null) {
            v.setQualityResult(qr.getResult());
            v.setInspector(qr.getInspector());
            v.setInspectTime(qr.getInspectTime() == null ? null : qr.getInspectTime().format(DTF));
            v.setQualityItems(parseJsonList(qr.getItems()));
        }
        DeliveryOrder d = t.getDeliveryId() == null ? null : deliveryMapper.selectById(t.getDeliveryId());
        if (d == null && order != null) {
            d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                    .eq(DeliveryOrder::getOrderId, order.getId()).orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));
        }
        if (d != null) {
            v.setDeliveryNo(d.getDeliveryNo());
            v.setVehicle(d.getVehicle());
            v.setDriver(d.getDriver());
            v.setTemperature(d.getTemperature());
            v.setMileage(d.getMileage());
        }
        InboundRecord ir = t.getInboundId() == null ? null : inboundMapper.selectById(t.getInboundId());
        if (ir == null && order != null) {
            ir = inboundMapper.selectOne(new LambdaQueryWrapper<InboundRecord>()
                    .eq(InboundRecord::getOrderId, order.getId()).orderByDesc(InboundRecord::getId).last("LIMIT 1"));
        }
        if (ir != null) {
            v.setReceiveQuantity(ir.getReceiveQuantity());
            v.setLossQuantity(ir.getLossQuantity());
            v.setLossRate(ir.getLossRate());
            v.setInboundTime(ir.getInboundTime() == null ? null : ir.getInboundTime().format(DTF));
        }
        if (order != null) {
            Settlement st = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                    .eq(Settlement::getOrderId, order.getId()).last("LIMIT 1"));
            if (st != null) {
                v.setActualAmount(st.getActualAmount());
            }
        }
        v.setNodes(buildNodes(t.getBatchNo()));
        v.setComplete(v.getReceiveQuantity() != null);
        return v;
    }

    /** 全链路时间线节点 */
    public List<Map<String, Object>> buildNodes(String batchNo) {
        TraceRecord t = traceMapper.selectOne(new LambdaQueryWrapper<TraceRecord>()
                .eq(TraceRecord::getBatchNo, batchNo).last("LIMIT 1"));
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (t == null) {
            return nodes;
        }
        Orders order = ordersMapper.selectById(t.getOrderId());
        Farmer farmer = farmerMapper.selectById(t.getFarmerId());
        Product product = productMapper.selectById(t.getProductId());
        Canteen canteen = order == null ? null : canteenMapper.selectById(order.getCanteenId());
        QualityReport qr = qualityMapper.selectOne(new LambdaQueryWrapper<QualityReport>()
                .eq(QualityReport::getBatchNo, batchNo).last("LIMIT 1"));
        DeliveryOrder d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, t.getOrderId()).orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));
        InboundRecord ir = inboundMapper.selectOne(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, t.getOrderId()).orderByDesc(InboundRecord::getId).last("LIMIT 1"));
        Settlement st = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getOrderId, t.getOrderId()).last("LIMIT 1"));

        nodes.add(node("农户发布货源 · 安排采摘",
                order == null ? "" : order.getCreateTime().format(DTF),
                farmer == null ? "-" : farmer.getContact() + "（" + farmer.getName() + "）",
                "农户按订单需求采摘" + (product == null ? "农产品" : product.getName()) + " "
                        + (order == null ? "" : order.getQuantity().stripTrailingZeros().toPlainString() + "kg")
                        + "，初检合格后安排发货，记录产地、地块与采收时间。"));
        if (qr != null) {
            nodes.add(node("第三方质检" + ("合格".equals(qr.getResult()) ? "合格" : "不合格") + " · 生成批次",
                    qr.getInspectTime() == null ? "" : qr.getInspectTime().format(DTF),
                    qr.getInspector() + "（质检配送员）",
                    "农残检测" + ("合格".equals(qr.getResult()) ? "合格" : "不达标") + "、含水率抽检、新鲜度评定；"
                            + ("合格".equals(qr.getResult())
                            ? "生成批次号 " + batchNo + " 并绑定溯源二维码。"
                            : "整批驳回，已扣减农户信用分。")));
        }
        if (d != null) {
            nodes.add(node("冷链配送出库",
                    (d.getStartTime() == null ? "" : d.getStartTime().format(DTF)),
                    "配送员 " + d.getDriver(),
                    "全程冷链 2~8℃，车牌 " + d.getVehicle() + "，扫码出库，GPS 轨迹实时上传；实时温度 "
                            + d.getTemperature() + "℃。"));
        }
        if (ir != null) {
            nodes.add(node("食堂确认入库 · 完成溯源",
                    ir.getInboundTime() == null ? "" : ir.getInboundTime().format(DTF),
                    ir.getOperatorName() + "（" + (canteen == null ? "" : canteen.getName()) + "采购经理）",
                    "扫码入库，实收 " + ir.getReceiveQuantity().stripTrailingZeros().toPlainString() + "kg"
                            + (ir.getLossQuantity().signum() > 0
                            ? "（损耗 " + ir.getLossQuantity().stripTrailingZeros().toPlainString() + "kg，损耗率 "
                            + ir.getLossRate() + "%），自动生成结算单并触发损耗结算。"
                            : "，数量相符，无损耗。")));
        }
        if (st != null) {
            nodes.add(node("结算单" + st.getStatus(),
                    st.getCreateTime() == null ? "" : st.getCreateTime().format(DTF),
                    st.getAuditor() == null ? "系统自动核算" : st.getAuditor() + "（系统管理员）",
                    "订单金额 ¥" + st.getTotalAmount() + "，损耗扣款 ¥" + st.getLossDeduction()
                            + "，实际结算 ¥" + st.getActualAmount() + "。"));
        }
        return nodes;
    }

    private Map<String, Object> node(String title, String time, String operator, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("time", time);
        m.put("operator", operator);
        m.put("desc", desc);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonList(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(s, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 最近批次（用于溯源页默认展示） */
    public List<Map<String, Object>> recent(int limit) {
        List<TraceRecord> list = traceMapper.selectList(new LambdaQueryWrapper<TraceRecord>()
                .orderByDesc(TraceRecord::getId).last("LIMIT " + limit));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TraceRecord t : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            Orders o = ordersMapper.selectById(t.getOrderId());
            Product p = productMapper.selectById(t.getProductId());
            Farmer f = farmerMapper.selectById(t.getFarmerId());
            m.put("batchNo", t.getBatchNo());
            m.put("orderNo", o == null ? "-" : o.getOrderNo());
            m.put("productName", p == null ? "-" : p.getName());
            m.put("farmerName", f == null ? "-" : f.getName());
            m.put("createTime", t.getCreateTime() == null ? "" : t.getCreateTime().format(DTF_S));
            out.add(m);
        }
        return out;
    }
}
