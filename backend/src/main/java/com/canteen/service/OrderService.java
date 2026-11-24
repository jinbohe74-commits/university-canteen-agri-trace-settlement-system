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
import com.canteen.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 订单状态机、查询与数据权限
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrdersMapper ordersMapper;
    private final OrderItemMapper itemMapper;
    private final QualityReportMapper qualityMapper;
    private final DeliveryOrderMapper deliveryMapper;
    private final InboundRecordMapper inboundMapper;
    private final SettlementMapper settlementMapper;
    private final TraceRecordMapper traceMapper;
    private final EvaluationMapper evaluationMapper;
    private final CreditScoreMapper creditMapper;
    private final SupplySourceMapper sourceMapper;
    private final PurchaseDemandMapper demandMapper;
    private final RefService refService;
    private final LogService logService;
    private final NotificationService notificationService;

    /** 状态机：当前状态 -> 允许的下一状态 */
    private static final Map<Integer, Set<Integer>> MACHINE = new java.util.HashMap<>();

    static {
        MACHINE.put(10, Set.of(20, 99));
        MACHINE.put(20, Set.of(30, 99));
        MACHINE.put(30, Set.of(40, 41));
        MACHINE.put(40, Set.of(50, 99));
        MACHINE.put(41, Set.of(99, 30));   // 修复：不合格批次允许退回待质检复检，不再是死路
        MACHINE.put(50, Set.of(60));
        MACHINE.put(60, Set.of(70, 99));
        MACHINE.put(70, Set.of(80));
        MACHINE.put(80, Set.of(90));
        MACHINE.put(90, Set.of(100));
        MACHINE.put(100, Set.of());
        MACHINE.put(99, Set.of());
    }

    public static boolean can(Integer from, Integer to) {
        if (from == null || to == null) {
            return false;
        }
        return MACHINE.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<Integer> next(int from) {
        return MACHINE.getOrDefault(from, Set.of());
    }

    /** 状态机字典（前端步骤条与状态说明） */
    public List<Map<String, Object>> statusMachine() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<Integer, String> e : RefService.ORDER_STATUS.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", e.getKey());
            m.put("name", e.getValue());
            m.put("next", next(e.getKey()).stream().map(n -> Map.of("status", n,
                    "name", RefService.statusName(n))).toList());
            list.add(m);
        }
        return list;
    }

    /** 分页查询（自动按角色拼接数据权限） */
    public PageResult<OrderVO> page(int page, int size, Integer status, Long canteenId, Long farmerId,
                                    String keyword, Integer days, String scope) {
        LambdaQueryWrapper<Orders> qw = new LambdaQueryWrapper<>();
        LoginUser lu = UserContext.get();
        if ("FARMER".equals(lu.getRole()) || "farmer".equals(scope)) {
            qw.eq(Orders::getFarmerId, lu.getFarmerId());
        } else if ("PURCHASER".equals(lu.getRole()) || "purchaser".equals(scope)) {
            qw.eq(Orders::getCanteenId, lu.getCanteenId());
        } else if ("INSPECTOR".equals(lu.getRole())) {
            qw.in(Orders::getStatus, List.of(30, 40, 50, 60));
        } else {
            if (canteenId != null) {
                qw.eq(Orders::getCanteenId, canteenId);
            }
        }
        if (farmerId != null) {
            qw.eq(Orders::getFarmerId, farmerId);
        }
        if (status != null) {
            qw.eq(Orders::getStatus, status);
        }
        if (days != null && days > 0) {
            qw.ge(Orders::getCreateTime, LocalDateTime.now().minusDays(days));
        }
        if (StringUtils.hasText(keyword)) {
            // 修复 P2：关键词参数绑定 + 剥离 LIKE 通配符，杜绝注入与 %25 绕过筛选
            String like = SqlLike.contains(keyword);
            if (like == null) {
                qw.apply("1 = 0");
            } else {
                qw.and(w -> w.apply("order_no LIKE {0}", like)
                        .or().apply("batch_no LIKE {0}", like)
                        .or().apply("farmer_id IN (SELECT id FROM farmer WHERE name LIKE {0})", like)
                        .or().apply("product_id IN (SELECT id FROM product WHERE name LIKE {0})", like));
            }
        }
        qw.orderByDesc(Orders::getCreateTime);
        IPage<Orders> p = ordersMapper.selectPage(new Page<>(page, size), qw);
        return PageResult.of(p, refService::toOrderVO);
    }

    /** 采购单下的第一张履约子单（单笔下单时用于回传订单号） */
    public Orders firstOfPo(Long poId) {
        return ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getPoId, poId).orderByAsc(Orders::getId).last("LIMIT 1"));
    }

    public Orders getById(Long id) {        Orders o = ordersMapper.selectById(id);
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        checkDataScope(o);
        return o;
    }

    public Orders getByNo(String orderNo) {
        Orders o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, orderNo));
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        return o;
    }

    public void checkDataScope(Orders o) {
        LoginUser lu = UserContext.get();
        if ("ADMIN".equals(lu.getRole())) {
            return;
        }
        if ("FARMER".equals(lu.getRole()) && !Objects.equals(o.getFarmerId(), lu.getFarmerId())) {
            throw new BizException(403, "无权访问其他农户的订单数据");
        }
        if ("PURCHASER".equals(lu.getRole()) && !Objects.equals(o.getCanteenId(), lu.getCanteenId())) {
            throw new BizException(403, "无权访问其他食堂的订单数据");
        }
    }

    /** 状态流转（乐观锁） */
    @Transactional
    public void changeStatus(Long orderId, int target, Integer version) {
        Orders o = ordersMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        checkDataScope(o);
        // 乐观锁：前端持有的版本号与库中不一致，说明订单已被他人修改
        if (version != null && !version.equals(o.getVersion())) {
            throw new BizException(409, "订单已被他人修改，请刷新后重试（乐观锁冲突）");
        }
        if (!can(o.getStatus(), target)) {
            throw new BizException("当前状态【" + RefService.statusName(o.getStatus()) + "】不允许变更为【"
                    + RefService.statusName(target) + "】");
        }
        if (version == null) {
            version = o.getVersion();
        }
        int rows = ordersMapper.updateStatusWithVersion(orderId, target, version);
        if (rows == 0) {
            throw new BizException(409, "订单已被他人修改，请刷新后重试（乐观锁冲突）");
        }
        logService.record("订单管理", "订单 " + o.getOrderNo() + " 状态变更为 " + RefService.statusName(target));
    }

    /** 订单详情（含明细、质检、配送、入库、结算、溯源、评价） */
    public Map<String, Object> detail(Long id) {
        Orders o = getById(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", refService.toOrderVO(o));
        m.put("items", itemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, id)));
        m.put("quality", qualityMapper.selectList(new LambdaQueryWrapper<QualityReport>()
                .eq(QualityReport::getOrderId, id).orderByDesc(QualityReport::getId)));
        m.put("delivery", deliveryMapper.selectList(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, id).orderByDesc(DeliveryOrder::getId)));
        m.put("inbound", inboundMapper.selectList(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, id)));
        m.put("settlement", settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getOrderId, id)));
        m.put("trace", traceMapper.selectList(new LambdaQueryWrapper<TraceRecord>()
                .eq(TraceRecord::getOrderId, id)));
        m.put("evaluation", evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getOrderId, id)));
        m.put("credit", creditMapper.selectList(new LambdaQueryWrapper<CreditScore>()
                .eq(CreditScore::getOrderId, id).orderByDesc(CreditScore::getId)));
        m.put("nextStatus", next(o.getStatus()));
        m.put("canUrge", o.getStatus() == 10 || o.getStatus() == 20);
        return m;
    }

    /** 农户接单 */
    @Transactional
    public void accept(Long orderId, Integer version) {
        Orders o = ordersMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        LoginUser lu = UserContext.get();
        if (!Objects.equals(o.getFarmerId(), lu.getFarmerId())) {
            throw new BizException(403, "只能操作本农户的订单");
        }
        changeStatus(orderId, 20, version);
        logService.record("订单管理", "确认接单 " + o.getOrderNo());
    }

    /** 农户拒单 */
    @Transactional
    public void reject(Long orderId, String reason, Integer version) {
        Orders o = ordersMapper.selectById(orderId);
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        if (!Objects.equals(o.getFarmerId(), UserContext.get().getFarmerId())) {
            throw new BizException(403, "只能操作本农户的订单");
        }
        changeStatus(orderId, 99, version);
        logService.record("订单管理", "拒单 " + o.getOrderNo() + "，原因：" + (reason == null ? "未说明" : reason));
    }

    /** 农户发货（进入待质检） */
    @Transactional
    public void ship(Long orderId, Integer version) {
        changeStatus(orderId, 30, version);
        Orders o = ordersMapper.selectById(orderId);
        if (o != null) {
            notificationService.toOrderParties(orderId, "SHIP", o.getOrderNo(),
                    "新批次待质检", "订单 " + o.getOrderNo() + " 已由农户发货，请安排到货质检",
                    Map.of("INSPECTOR", "/inspector/tasks", "CANTEEN", "/purchaser/orders"));
        }
    }

    /** 订单取消 */
    @Transactional
    public void cancel(Long orderId, String reason, Integer version) {
        Orders o = getById(orderId);
        if (o.getStatus() >= 70) {
            throw new BizException("订单已入库，无法取消，请联系管理员处理");
        }
        changeStatus(orderId, 99, version);
        logService.record("订单管理", "取消订单 " + o.getOrderNo() + "，原因：" + (reason == null ? "未说明" : reason));
    }

    /** 更新批次号 */
    public void bindBatch(Long orderId, String batchNo) {
        ordersMapper.updateBatchNo(orderId, batchNo);
    }

    /** 订单统计（按状态） */
    public Map<String, Object> statistics(String scope) {
        Map<String, Object> m = new LinkedHashMap<>();
        LoginUser lu = UserContext.get();
        for (Integer s : RefService.ORDER_STATUS.keySet()) {
            LambdaQueryWrapper<Orders> qw = new LambdaQueryWrapper<Orders>().eq(Orders::getStatus, s);
            if ("FARMER".equals(lu.getRole())) {
                qw.eq(Orders::getFarmerId, lu.getFarmerId());
            } else if ("PURCHASER".equals(lu.getRole())) {
                qw.eq(Orders::getCanteenId, lu.getCanteenId());
            }
            m.put(String.valueOf(s), ordersMapper.selectCount(qw));
        }
        return m;
    }

    /** 最近 N 天是否有未完成订单（催办提示） */
    public List<Orders> overdueOrders(int days, int limit) {
        return ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .in(Orders::getStatus, List.of(10, 20))
                .lt(Orders::getCreateTime, LocalDateTime.now().minusDays(days))
                .last("LIMIT " + limit));
    }

    /** 采购员下单：选择货源生成订单（校验库存、扣减可供量） */
    @Transactional
    public Orders create(Long demandId, Long sourceId, java.math.BigDecimal quantity,
                         LocalDateTime expectedDelivery) {
        return create(demandId, sourceId, quantity, expectedDelivery, null);
    }

    /**
     * 带采购单归属的下单。一次提交多个品项时由 {@link PurchaseOrderService} 逐项调用，
     * 明细日志由采购单统一记一条，避免一次采购刷出几十条操作日志。
     */
    @Transactional
    public Orders create(Long demandId, Long sourceId, java.math.BigDecimal quantity,
                         LocalDateTime expectedDelivery, Long poId) {
        SupplySource s = sourceMapper.selectById(sourceId);
        if (s == null) {
            throw new BizException(404, "货源不存在");
        }
        if (!"正常".equals(s.getStatus())) {
            throw new BizException("该货源当前不可下单（" + s.getStatus() + "）");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new BizException(400, "下单数量必须大于 0");
        }
        if (s.getQuantity() == null || s.getQuantity().signum() <= 0) {
            throw new BizException(409, "该货源已无可供应量");
        }
        if (quantity.compareTo(s.getQuantity()) > 0) {
            throw new BizException("下单数量不能超过货源可供数量（剩余 " + s.getQuantity() + s.getUnit() + "）");
        }
        // 修复 P1-5：成交单价必须为正，防止生成负/零金额订单
        if (s.getPrice() == null || s.getPrice().signum() <= 0) {
            throw new BizException(400, "货源报价必须大于 0，无法下单");
        }
        // 修复 P1-10：原子扣减库存并检查影响行数；失败时事务整体回滚，不会留下半条订单
        if (sourceMapper.deductStock(sourceId, quantity) == 0) {
            throw new BizException(409, "货源库存不足或已被其他订单占用，请刷新后重试");
        }
        PurchaseDemand d = demandId == null ? null : demandMapper.selectById(demandId);
        Long canteenId = d != null ? d.getCanteenId() : UserContext.canteenId();
        if (d != null && !Objects.equals(d.getCanteenId(), UserContext.canteenId())) {
            throw new BizException(403, "只能为本人所属食堂下单");
        }
        Orders o = new Orders();
        String datePart = "DD" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        o.setOrderNo(com.canteen.common.Codes.next(datePart, 4, (int) (ordersMapper.selectCount(null) % 9000 + 1),
                code -> ordersMapper.selectCount(new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, code)) > 0));
        o.setDemandId(demandId);
        o.setPoId(poId);
        o.setSourceId(sourceId);
        o.setFarmerId(s.getFarmerId());
        o.setCanteenId(canteenId);
        o.setProductId(s.getProductId());
        o.setQuantity(quantity);
        o.setUnitPrice(s.getPrice());
        o.setTotalAmount(quantity.multiply(s.getPrice()).setScale(2, java.math.RoundingMode.HALF_UP));
        o.setStatus(10);
        o.setExpectedDelivery(expectedDelivery == null ? LocalDateTime.now().plusDays(1) : expectedDelivery);
        o.setVersion(0);
        o.setCreateTime(LocalDateTime.now());
        ordersMapper.insert(o);

        OrderItem item = new OrderItem();
        item.setOrderId(o.getId());
        item.setProductId(s.getProductId());
        item.setOrderQuantity(quantity);
        item.setActualQuantity(java.math.BigDecimal.ZERO);
        item.setLossQuantity(java.math.BigDecimal.ZERO);
        item.setUnitPrice(s.getPrice());
        item.setCreateTime(LocalDateTime.now());
        itemMapper.insert(item);

        if (d != null) {
            d.setStatus("已成交");
            demandMapper.updateById(d);
        }
        if (poId == null) {
            logService.record("订单管理", "生成采购订单 " + o.getOrderNo() + "，农户 #" + s.getFarmerId()
                    + "，金额 ¥" + o.getTotalAmount());
        }
        return o;
    }
}



