package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
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
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 配送任务：冷链车辆、车厢温度、GPS 轨迹、扫码交付
 */
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final DeliveryOrderMapper deliveryMapper;
    private final DeliveryTempLogMapper tempLogMapper;
    private final OrdersMapper ordersMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final ProductMapper productMapper;
    private final TraceRecordMapper traceMapper;
    private final RefService refService;
    private final OrderService orderService;
    private final TraceService traceService;
    private final LogService logService;
    private final NotificationService notificationService;
    private final ObjectMapper json = new ObjectMapper();

    private static final String[] VEHICLES = {"赣H·D2867", "赣H·D2912", "赣H·D2688", "赣H·D2791"};
    private static final String[] DRIVERS = {"钱师傅", "孙师傅", "李师傅", "王师傅"};

    /**
     * 配送工作台：待办作业单 + 任务清单。
     *
     * <p>原来 current 的取法是「有配送中的就用它，否则取列表第一条」——而列表第一条是
     * 创建时间最早的历史单，于是操作员一进页面看到的是上个月已经交付完的单子被当成
     * 「当前配送任务」，下面还挂着可点的发车/扫码按钮，整页看着就是别扭。</p>
     *
     * <p>现在：current 只在「配送中」或「待发车」里挑，两者都没有就返回 null，
     * 由前端显示「当前没有待办的配送任务」，而不是拿历史单冒充当前工作。</p>
     */
    public Map<String, Object> tasks() {
        List<Orders> candidates = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .in(Orders::getStatus, List.of(40, 50, 60))
                .orderByAsc(Orders::getCreateTime));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Orders o : candidates) {
            DeliveryOrder d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                    .eq(DeliveryOrder::getOrderId, o.getId()).orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));
            if (d == null) {
                // 修复 P1-11：GET 查询不再隐式写库；配送单由质检合格的显式写操作创建
                continue;
            }
            rows.add(view(d, o));
        }
        // 待办优先：在途 > 待发车；都没有就不指定 current
        Map<String, Object> shipping = null;
        Map<String, Object> waiting = null;
        long nWaiting = 0, nShipping = 0, nDelivered = 0;
        for (Map<String, Object> r : rows) {
            String st = String.valueOf(r.get("status"));
            if ("配送中".equals(st)) {
                nShipping++;
                if (shipping == null) shipping = r;
            } else if ("待发车".equals(st)) {
                nWaiting++;
                if (waiting == null) waiting = r;
            } else if ("已送达".equals(st)) {
                nDelivered++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", shipping != null ? shipping : waiting);
        m.put("records", rows);
        m.put("total", rows.size());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("waiting", nWaiting);
        counts.put("shipping", nShipping);
        counts.put("delivered", nDelivered);
        m.put("counts", counts);
        m.put("vehicles", Arrays.asList(VEHICLES));
        m.put("drivers", Arrays.asList(DRIVERS));
        // 温度预警
        List<Map<String, Object>> alarms = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object t = r.get("temperature");
            if (t != null && ((BigDecimal) t).compareTo(new BigDecimal("8")) > 0) {
                alarms.add(Map.of("deliveryNo", r.get("deliveryNo"), "temperature", t,
                        "message", "车厢温度超出 2~8℃ 范围"));
            }
        }
        m.put("alarms", alarms);
        return m;
    }

    @Transactional
    public DeliveryOrder createFor(Orders o) {
        long count = deliveryMapper.selectCount(null);
        int idx = (int) (count % VEHICLES.length);
        Farmer f = farmerMapper.selectById(o.getFarmerId());
        Canteen c = canteenMapper.selectById(o.getCanteenId());
        DeliveryOrder d = new DeliveryOrder();
        String dps = "PS" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        d.setDeliveryNo(com.canteen.common.Codes.next(dps, 3, (int) (count % 800 + 100),
                code -> deliveryMapper.selectCount(new LambdaQueryWrapper<DeliveryOrder>().eq(DeliveryOrder::getDeliveryNo, code)) > 0));
        d.setOrderId(o.getId());
        d.setVehicle(VEHICLES[idx]);
        d.setDriver(DRIVERS[idx]);
        d.setDriverPhone("138****01" + String.format("%02d", 50 + idx));
        d.setTemperature(new BigDecimal("6.0"));
        d.setMileage(f == null ? new BigDecimal("10") : f.getDistanceKm());
        d.setOrigin(f == null ? "供货基地" : f.getName() + "基地");
        d.setDestination(c == null ? "食堂" : c.getName());
        d.setStatus(o.getStatus() >= 60 ? "已送达" : (o.getStatus() == 50 ? "配送中" : "待发车"));
        if (o.getStatus() >= 50) {
            d.setStartTime(LocalDateTime.now().minusMinutes(40));
        }
        if (o.getStatus() >= 60) {
            d.setArriveTime(LocalDateTime.now().minusMinutes(10));
        }
        d.setTraces(buildTraces(d, o));
        d.setCreateTime(LocalDateTime.now());
        deliveryMapper.insert(d);
        return d;
    }

    private String buildTraces(DeliveryOrder d, Orders o) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        LocalDateTime t0 = d.getStartTime() == null ? LocalDateTime.now() : d.getStartTime();
        // 没发车就不存在「装货完成 / 运输中」，原来这两个节点恒为 true，
        // 待发车的单会显示成 2/4，等于凭空多出两个已发生的节点。
        boolean started = d.getStartTime() != null;
        boolean arrived = "已送达".equals(d.getStatus());
        nodes.add(Map.of("time", t0.format(HM), "title", d.getOrigin() + "装货", "done", started));
        nodes.add(Map.of("time", t0.plusMinutes(20).format(HM), "title", "冷链运输中", "done", started));
        nodes.add(Map.of("time", t0.plusMinutes(55).format(HM), "title", "到达 " + d.getDestination(), "done", arrived));
        nodes.add(Map.of("time", (d.getArriveTime() == null ? t0.plusMinutes(65) : d.getArriveTime()).format(HM),
                "title", "扫码交付入库", "done", arrived));
        try {
            return json.writeValueAsString(nodes);
        } catch (Exception e) {
            return "[]";
        }
    }

    private Map<String, Object> view(DeliveryOrder d, Orders o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("deliveryNo", d.getDeliveryNo());
        m.put("orderNo", o.getOrderNo());
        m.put("orderId", o.getId());
        m.put("vehicle", d.getVehicle());
        m.put("driver", d.getDriver());
        m.put("driverPhone", d.getDriverPhone());
        m.put("temperature", d.getTemperature());
        m.put("mileage", d.getMileage());
        m.put("origin", d.getOrigin());
        m.put("destination", d.getDestination());
        m.put("status", d.getStatus());
        m.put("orderStatus", o.getStatus());
        m.put("orderStatusName", RefService.statusName(o.getStatus()));
        m.put("startTime", d.getStartTime());
        m.put("arriveTime", d.getArriveTime());
        m.put("batchNo", o.getBatchNo());
        Product p = productMapper.selectById(o.getProductId());
        m.put("productName", p == null ? "-" : p.getName());
        m.put("quantity", o.getQuantity());
        m.put("unit", p == null ? "kg" : p.getUnit());
        m.put("temperatureAlarm", d.getTemperature() != null
                && (d.getTemperature().compareTo(new BigDecimal("8")) > 0
                || d.getTemperature().compareTo(new BigDecimal("2")) < 0));
        // 交付核对用的批次号：整串和末 6 位都要下发。
        // 原来这里只给 "04****" 掩码，操作员站在货箱前也永远凑不出后 4 位，
        // 输入任何自认为正确的值都会被判错（假失败）。标签本来就贴在货上，
        // 把批次号露出来才是"核对是不是这一箱"，藏起来只是制造误判。
        String batch = o.getBatchNo();
        m.put("batchTail", batch == null || batch.isBlank() ? null : tail(batch, 6));
        m.put("batchTailLen", batch == null || batch.isBlank() ? 0 : Math.min(6, batch.length()));
        Farmer f = farmerMapper.selectById(o.getFarmerId());
        Canteen c = canteenMapper.selectById(o.getCanteenId());
        m.put("originLng", f == null ? null : f.getLng());
        m.put("originLat", f == null ? null : f.getLat());
        m.put("destLng", c == null ? null : c.getLng());
        m.put("destLat", c == null ? null : c.getLat());
        try {
            List<Map<String, Object>> nodes = json.readValue(d.getTraces() == null ? "[]" : d.getTraces(), List.class);
            // 历史数据里的 done 不可信：早期种子数据没有这个字段（已送达也显示 0/4），
            // 早期发车前生成的数据把「装货/运输中」恒标为完成（待发车却显示 2/4）。
            // 这里按状态统一裁决，节点只表达"这一步有没有发生"，不依赖落库时是否规范。
            boolean arrived = "已送达".equals(d.getStatus());
            boolean started = d.getStartTime() != null;
            for (int i = 0; i < nodes.size(); i++) {
                Map<String, Object> n = nodes.get(i);
                boolean done = arrived || (started && i < 2);
                n.put("done", done);
            }
            m.put("traces", nodes);
        } catch (Exception e) {
            m.put("traces", List.of());
        }
        return m;
    }

    /**
     * 发车（原「开始配送」）。
     *
     * <p>改造前点一下就发车，不问车辆、不问温度。现在要求三件事都成立：
     * 订单已质检通过、起始车厢温度在 2~8℃、车辆与司机已确认。
     * 起始温度会写入温度流水，作为「这一路有没有断链」的起点。</p>
     *
     * @param vehicle  司机确认的车辆（空则沿用派车时的）
     * @param driver   司机姓名（空则沿用）
     * @param startTemp 发车时实测车厢温度（空则用当前记录值）
     */
    @Transactional
    public Map<String, Object> start(Long deliveryId, String vehicle, String driver, BigDecimal startTemp) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        Orders o = ordersMapper.selectById(d.getOrderId());
        if (o == null) {
            throw new BizException(404, "关联订单不存在");
        }
        orderService.checkDataScope(o);
        if ("已送达".equals(d.getStatus())) {
            throw new BizException("该配送单已送达，不能重复发车");
        }
        if ("配送中".equals(d.getStatus())) {
            throw new BizException("该配送单已在配送中，无需重复发车");
        }
        // 只有质检放行的批次才允许发车：不合格批次从这里就被挡住
        if (o.getStatus() != 40) {
            throw new BizException("订单当前为【" + RefService.statusName(o.getStatus())
                    + "】，仅【质检通过】的批次可以发车");
        }
        BigDecimal temp = startTemp == null ? d.getTemperature() : startTemp;
        if (temp == null) {
            throw new BizException("请填写发车时实测的车厢温度");
        }
        if (temp.compareTo(new BigDecimal("2")) < 0 || temp.compareTo(new BigDecimal("8")) > 0) {
            throw new BizException("车厢温度 " + temp + "℃ 超出冷链 2~8℃，请先处理温度异常再发车");
        }
        if (vehicle != null && !vehicle.isBlank()) {
            if (vehicle.length() > 30) {
                throw new BizException("车牌号过长");
            }
            d.setVehicle(vehicle.trim());
        }
        if (driver != null && !driver.isBlank()) {
            if (driver.length() > 30) {
                throw new BizException("司机姓名过长");
            }
            d.setDriver(driver.trim());
        }
        if (d.getVehicle() == null || d.getVehicle().isBlank()
                || d.getDriver() == null || d.getDriver().isBlank()) {
            throw new BizException("发车前必须确认车辆与司机");
        }
        // 随车批次：必须至少有一个批次号，否则到了食堂无法核对
        if (o.getBatchNo() == null || o.getBatchNo().isBlank()) {
            throw new BizException("该订单还没有批次号，请先完成批次质检");
        }

        d.setTemperature(temp);
        d.setStatus("配送中");
        d.setStartTime(LocalDateTime.now());
        d.setTraces(buildTraces(d, o));
        deliveryMapper.updateById(d);
        writeTempLog(deliveryId, temp, "depart");
        if (o.getStatus() == 40) {
            orderService.changeStatus(o.getId(), 50, o.getVersion());
        }
        logService.record("配送任务", "配送单 " + d.getDeliveryNo() + " 发车（" + d.getVehicle()
                + " / " + d.getDriver() + "，起始温度 " + temp + "℃）");
        notificationService.toOrderParties(o.getId(), "DEPART", o.getOrderNo(),
                "批次已发车", "订单 " + o.getOrderNo() + " 已发车（" + d.getVehicle() + " / " + d.getDriver()
                        + "，车厢 " + temp + "℃），请准备收货",
                Map.of("CANTEEN", "/purchaser/orders", "FARMER", "/farmer/orders"));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("deliveryNo", d.getDeliveryNo());
        res.put("vehicle", d.getVehicle());
        res.put("driver", d.getDriver());
        res.put("startTime", d.getStartTime());
        res.put("temperature", temp);
        res.put("batchNo", o.getBatchNo());
        return res;
    }

    /**
     * 扫码交付（原「扫码交付」只是点按钮）。
     *
     * <p>现在必须录入批次号后 6 位并校验通过——等价于现场扫枪扫到的内容。
     * 校验失败不允许放行，并提示应核对哪一位。</p>
     */
    @Transactional
    public Map<String, Object> arrive(Long deliveryId, String batchCode) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        Orders o = ordersMapper.selectById(d.getOrderId());
        if (o == null) {
            throw new BizException(404, "关联订单不存在");
        }
        orderService.checkDataScope(o);
        if ("已送达".equals(d.getStatus())) {
            throw new BizException("该配送单已交付，不能重复操作");
        }
        if (!"配送中".equals(d.getStatus())) {
            throw new BizException("配送单尚未发车，请先完成发车检查");
        }
        String batch = o.getBatchNo();
        if (batch == null || batch.isBlank()) {
            throw new BizException("订单缺少批次号，无法扫码核对");
        }
        String expected = tail(batch, 6);
        String given = normalizeCode(batchCode);
        if (given.isEmpty()) {
            throw new BizException("请扫描或录入随货批次号后再交付");
        }
        if (!matchesBatch(batch, given)) {
            logService.record("配送任务", "配送单 " + d.getDeliveryNo() + " 扫码交付校验失败（录入 "
                    + given + "，应为批次 " + batch + "）", "失败");
            throw new BizException("扫描到的批次与本单不符：本单批次 " + batch + "（尾号 " + expected
                    + "），请核对货箱标签后重试");
        }
        if (o.getStatus() == 50) {
            orderService.changeStatus(o.getId(), 60, o.getVersion());
            ordersMapper.updateActualDelivery(o.getId(), LocalDateTime.now());
        }
        d.setStatus("已送达");
        d.setArriveTime(LocalDateTime.now());
        d.setTraces(buildTraces(d, o));
        deliveryMapper.updateById(d);
        writeTempLog(deliveryId, d.getTemperature(), "manual");
        traceService.appendNode(o.getId());
        logService.record("配送任务", "配送单 " + d.getDeliveryNo() + " 扫码交付（批次尾号 " + expected + " 核对一致）");
        notificationService.toOrderParties(o.getId(), "ARRIVE", o.getOrderNo(),
                "已送达，待验收入库", "订单 " + o.getOrderNo() + " 批次 " + batch + " 已扫码交付，请及时实秤验收入库",
                Map.of("CANTEEN", "/purchaser/inbound"));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("deliveryNo", d.getDeliveryNo());
        res.put("batchNo", batch);
        res.put("arriveTime", d.getArriveTime());
        return res;
    }

    /**
     * 扫码交付的判定：扫枪吐整串条码，手抄通常只有尾号，两种都得认。
     *
     * <p>认作同一批次的三类输入：整串批次号、末 6 位、整串的 ≥6 位后缀。
     * 只认「末 6 位完全相等」会把「扫到别的车」和「格式不合」混成同一种失败，
     * 现场根本分不清是自己扫错了还是系统不认。</p>
     */
    public static boolean matchesBatch(String batch, String given) {
        String full = normalizeCode(batch);
        String code = normalizeCode(given);
        if (full.isEmpty() || code.isEmpty()) {
            return false;
        }
        return code.equals(full) || code.equals(tail(batch, 6))
                || (code.length() >= 6 && full.endsWith(code));
    }

    /** 取字符串末尾 n 位并转大写 */
    static String tail(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s.toUpperCase(Locale.ROOT)
                : s.substring(s.length() - n).toUpperCase(Locale.ROOT);
    }

    /** 归一化扫码结果：去掉空格、横杠等分隔符并转大写，避免「扫对了却因格式判错」 */
    static String normalizeCode(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }

    private void writeTempLog(Long deliveryId, BigDecimal temp, String source) {
        if (deliveryId == null || temp == null) {
            return;
        }
        DeliveryTempLog rec = new DeliveryTempLog();
        rec.setDeliveryId(deliveryId);
        rec.setTemperature(temp);
        rec.setAlarm(temp.compareTo(new BigDecimal("2")) < 0 || temp.compareTo(new BigDecimal("8")) > 0 ? 1 : 0);
        rec.setSource(source);
        rec.setReportTime(LocalDateTime.now());
        tempLogMapper.insert(rec);
    }

    /** 更新车厢温度（模拟冷链上报） */
    @Transactional
    public void updateTemperature(Long deliveryId, BigDecimal temperature) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        Orders o = ordersMapper.selectById(d.getOrderId());
        if (o == null) {
            throw new BizException(404, "关联订单不存在");
        }
        orderService.checkDataScope(o);   // 修复 P1-3
        if (temperature == null || temperature.compareTo(new BigDecimal("-30")) < 0
                || temperature.compareTo(new BigDecimal("60")) > 0) {
            throw new BizException(400, "车厢温度超出合理范围");
        }
        d.setTemperature(temperature);
        deliveryMapper.updateById(d);
        writeTempLog(deliveryId, temperature, "manual");
    }

    /** 公路平均速度（km/h）：用于按已行驶时间推算位置与到达时间 */
    private static final double AVG_SPEED_KMH = 32.0;

    /**
     * 配送实时轨迹：车辆位置、已行驶/剩余里程、预计到达、温度曲线。
     *
     * <p>位置按「发车至今的实际分钟数 × 平均车速」沿起终点连线插值，
     * 所有输入（发车时刻、里程、坐标、温度流水）都来自库里的真实字段，
     * 不生成任何"好看的假轨迹"。前端会标注路线为里程示意、实际以导航为准。</p>
     */
    /** 跨角色按订单查轨迹：订单详情抽屉所有角色都要能看，先按订单找配送单。
        权限仍由 track() 内的 checkDataScope 兜底。 */
    public Map<String, Object> trackByOrder(Long orderId) {
        // 无论有没有配送单，先做订单数据权限校验，避免用它探测他人订单是否存在
        Orders scoped = ordersMapper.selectById(orderId);
        if (scoped == null) {
            throw new BizException(404, "订单不存在");
        }
        orderService.checkDataScope(scoped);
        DeliveryOrder d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, orderId)
                .orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));
        if (d == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hasGeo", false);
            m.put("reason", "该订单尚未生成配送单，暂无轨迹");
            return m;
        }
        return track(d.getId());
    }

    public Map<String, Object> track(Long deliveryId) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        Orders o = ordersMapper.selectById(d.getOrderId());
        if (o == null) {
            throw new BizException(404, "关联订单不存在");
        }
        orderService.checkDataScope(o);
        Farmer f = farmerMapper.selectById(o.getFarmerId());
        Canteen c = canteenMapper.selectById(o.getCanteenId());

        // 缺坐标时退化为「食堂点位」单点，不编造一段不存在的行程
        boolean hasGeo = f != null && f.getLng() != null && f.getLat() != null
                && c != null && c.getLng() != null && c.getLat() != null;
        double fromLng = hasGeo ? f.getLng().doubleValue() : 0;
        double fromLat = hasGeo ? f.getLat().doubleValue() : 0;
        double toLng = hasGeo ? c.getLng().doubleValue() : 0;
        double toLat = hasGeo ? c.getLat().doubleValue() : 0;
        double straightKm = hasGeo ? haversine(fromLat, fromLng, toLat, toLng) : 0;

        double roadKm = d.getMileage() == null ? straightKm : d.getMileage().doubleValue();
        if (roadKm <= 0) {
            roadKm = straightKm;
        }

        String status = d.getStatus();
        double progress = 0;
        double doneKm = 0;
        Integer etaMinutes = null;
        LocalDateTime eta = null;
        LocalDateTime now = LocalDateTime.now();
        if ("配送中".equals(status)) {
            LocalDateTime st = d.getStartTime();
            double elapsedMin = st == null ? 0 : Math.max(0, java.time.Duration.between(st, now).toMinutes());
            doneKm = Math.min(roadKm, elapsedMin / 60.0 * AVG_SPEED_KMH);
            progress = roadKm <= 0 ? 0 : Math.min(1, doneKm / roadKm);
            double remainKm = Math.max(0, roadKm - doneKm);
            etaMinutes = (int) Math.ceil(remainKm / AVG_SPEED_KMH * 60);
            eta = now.plusMinutes(etaMinutes);
        } else if ("已送达".equals(status)) {
            progress = 1;
            doneKm = roadKm;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deliveryId", d.getId());
        m.put("deliveryNo", d.getDeliveryNo());
        m.put("orderNo", o.getOrderNo());
        m.put("batchNo", o.getBatchNo());
        m.put("status", status);
        m.put("vehicle", d.getVehicle());
        m.put("driver", d.getDriver());
        m.put("hasGeo", hasGeo);
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("name", d.getOrigin());
        from.put("lng", hasGeo ? fromLng : null);
        from.put("lat", hasGeo ? fromLat : null);
        Map<String, Object> to = new LinkedHashMap<>();
        to.put("name", d.getDestination());
        to.put("lng", hasGeo ? toLng : null);
        to.put("lat", hasGeo ? toLat : null);
        m.put("origin", from);
        m.put("destination", to);

        // 路线示意：沿起终点做一条轻微弯曲的二次贝塞尔，只用于视觉，不宣称是真实路网
        List<double[]> path = new ArrayList<>();
        if (hasGeo) {
            double midLng = (fromLng + toLng) / 2;
            double midLat = (fromLat + toLat) / 2;
            double dx = toLng - fromLng;
            double dy = toLat - fromLat;
            // 垂直于连线方向偏移 12%，避免和两地直线完全重合
            double ctrlLng = midLng - dy * 0.12;
            double ctrlLat = midLat + dx * 0.12;
            int steps = 24;
            for (int i = 0; i <= steps; i++) {
                double t = i / (double) steps;
                double x = (1 - t) * (1 - t) * fromLng + 2 * (1 - t) * t * ctrlLng + t * t * toLng;
                double y = (1 - t) * (1 - t) * fromLat + 2 * (1 - t) * t * ctrlLat + t * t * toLat;
                path.add(new double[]{round6(x), round6(y)});
            }
        }
        m.put("path", path);

        // 车辆位置取路径上的进度点，保证「车在路上」而不是在两点连线上飘
        Map<String, Object> pos = new LinkedHashMap<>();
        if (hasGeo && !path.isEmpty()) {
            int idx = (int) Math.round(progress * (path.size() - 1));
            double[] p = path.get(Math.max(0, Math.min(path.size() - 1, idx)));
            pos.put("lng", p[0]);
            pos.put("lat", p[1]);
        } else {
            pos.put("lng", null);
            pos.put("lat", null);
        }
        m.put("position", pos);
        m.put("pathIndex", hasGeo && !path.isEmpty()
                ? (int) Math.round(progress * (path.size() - 1)) : -1);

        m.put("progress", round4(progress));
        m.put("doneKm", round1(doneKm));
        m.put("remainKm", round1(Math.max(0, roadKm - doneKm)));
        m.put("roadKm", round1(roadKm));
        m.put("straightKm", round1(straightKm));
        m.put("speedKmh", "配送中".equals(status) ? AVG_SPEED_KMH : 0);
        m.put("etaMinutes", etaMinutes);
        m.put("eta", eta == null ? null : eta.format(DateTimeFormatter.ofPattern("HH:mm")));
        m.put("startTime", d.getStartTime());
        m.put("arriveTime", d.getArriveTime());
        m.put("serverTime", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        m.put("temperature", d.getTemperature());
        m.put("temperatureAlarm", d.getTemperature() != null
                && (d.getTemperature().compareTo(new BigDecimal("8")) > 0
                || d.getTemperature().compareTo(new BigDecimal("2")) < 0));
        List<DeliveryTempLog> logs = new ArrayList<>(tempLogMapper.recent(deliveryId, 20));
        Collections.reverse(logs);
        List<Map<String, Object>> series = new ArrayList<>();
        for (DeliveryTempLog t : logs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", t.getReportTime() == null ? "" : t.getReportTime().format(HHMMSS));
            row.put("temperature", t.getTemperature());
            row.put("alarm", t.getAlarm() != null && t.getAlarm() == 1);
            row.put("source", t.getSource());
            series.add(row);
        }
        m.put("tempSeries", series);
        m.put("tempLogCount", series.size());
        return m;
    }

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 球面距离（km），Haversine */
    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private double round6(double v) {
        return BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** 配送单详情 */
    public Map<String, Object> detail(Long id) {        DeliveryOrder d = deliveryMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        Orders o = ordersMapper.selectById(d.getOrderId());
        if (o == null) {
            throw new BizException(404, "关联订单不存在");
        }
        orderService.checkDataScope(o);   // 修复 P1-3
        Map<String, Object> m = view(d, o);
        List<TraceRecord> traces = traceMapper.selectList(new LambdaQueryWrapper<TraceRecord>()
                .eq(TraceRecord::getOrderId, d.getOrderId()));
        m.put("traceRecords", traces);
        return m;
    }

    /** 结束配送（供质检员任务页使用） */
    public List<Map<String, Object>> todayList() {
        List<Orders> list = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .in(Orders::getStatus, List.of(40, 50, 60))
                .orderByDesc(Orders::getUpdateTime).last("LIMIT 20"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Orders o : list) {
            DeliveryOrder d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                    .eq(DeliveryOrder::getOrderId, o.getId()).orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));
            rows.add(d == null ? Map.of("orderNo", o.getOrderNo()) : view(d, o));
        }
        return rows;
    }
}

