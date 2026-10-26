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
    private final OrdersMapper ordersMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final ProductMapper productMapper;
    private final TraceRecordMapper traceMapper;
    private final RefService refService;
    private final OrderService orderService;
    private final TraceService traceService;
    private final LogService logService;
    private final ObjectMapper json = new ObjectMapper();

    private static final String[] VEHICLES = {"赣H·D2867", "赣H·D2912", "赣H·D2688", "赣H·D2791"};
    private static final String[] DRIVERS = {"钱师傅", "孙师傅", "李师傅", "王师傅"};

    /** 配送工作台：当前任务 + 今日任务表 */
    public Map<String, Object> tasks() {
        List<Orders> candidates = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .in(Orders::getStatus, List.of(40, 50, 60))
                .orderByAsc(Orders::getCreateTime));
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> current = null;
        for (Orders o : candidates) {
            DeliveryOrder d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                    .eq(DeliveryOrder::getOrderId, o.getId()).orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));
            if (d == null) {
                d = createFor(o);
            }
            Map<String, Object> row = view(d, o);
            rows.add(row);
            if (current == null || Objects.equals(d.getId(), latestActive())) {
                current = row;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", current);
        m.put("records", rows);
        m.put("total", rows.size());
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

    private Long latestActive() {
        DeliveryOrder d = deliveryMapper.selectOne(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getStatus, "配送中").orderByDesc(DeliveryOrder::getId).last("LIMIT 1"));
        return d == null ? null : d.getId();
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
        nodes.add(Map.of("time", t0.format(HM), "title", d.getOrigin() + "装货", "done", true));
        nodes.add(Map.of("time", t0.plusMinutes(20).format(HM), "title", "冷链运输中", "done", true));
        nodes.add(Map.of("time", t0.plusMinutes(55).format(HM), "title", "到达 " + d.getDestination(), "done",
                "已送达".equals(d.getStatus())));
        nodes.add(Map.of("time", (d.getArriveTime() == null ? t0.plusMinutes(65) : d.getArriveTime()).format(HM),
                "title", "扫码交付入库", "done", "已送达".equals(d.getStatus())));
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
        try {
            m.put("traces", json.readValue(d.getTraces() == null ? "[]" : d.getTraces(), List.class));
        } catch (Exception e) {
            m.put("traces", List.of());
        }
        return m;
    }

    /** 开始配送：订单 40 -> 50 */
    @Transactional
    public void start(Long deliveryId) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        Orders o = ordersMapper.selectById(d.getOrderId());
        if (o.getStatus() == 40) {
            orderService.changeStatus(o.getId(), 50, o.getVersion());
        }
        d.setStatus("配送中");
        d.setStartTime(LocalDateTime.now());
        d.setTraces(buildTraces(d, o));
        deliveryMapper.updateById(d);
        logService.record("配送任务", "配送单 " + d.getDeliveryNo() + " 开始配送");
    }

    /** 送达：订单 50 -> 60 */
    @Transactional
    public void arrive(Long deliveryId) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        Orders o = ordersMapper.selectById(d.getOrderId());
        if (o.getStatus() == 50) {
            orderService.changeStatus(o.getId(), 60, o.getVersion());
            ordersMapper.updateActualDelivery(o.getId(), LocalDateTime.now());
        }
        d.setStatus("已送达");
        d.setArriveTime(LocalDateTime.now());
        d.setTraces(buildTraces(d, o));
        deliveryMapper.updateById(d);
        traceService.appendNode(o.getId());
        logService.record("配送任务", "配送单 " + d.getDeliveryNo() + " 扫码交付，订单已送达");
    }

    /** 更新车厢温度（模拟冷链上报） */
    @Transactional
    public void updateTemperature(Long deliveryId, BigDecimal temperature) {
        DeliveryOrder d = deliveryMapper.selectById(deliveryId);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        d.setTemperature(temperature);
        deliveryMapper.updateById(d);
    }

    /** 配送单详情 */
    public Map<String, Object> detail(Long id) {
        DeliveryOrder d = deliveryMapper.selectById(id);
        if (d == null) {
            throw new BizException(404, "配送单不存在");
        }
        Orders o = ordersMapper.selectById(d.getOrderId());
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

