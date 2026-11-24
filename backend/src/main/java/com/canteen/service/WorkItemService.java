package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 单据工作台（Work Item）—— 第六轮整改的统一「定位到具体业务对象」契约。
 *
 * <p>以前通知 / 待办 / KPI 明细的点击终点都是「router.push(某个列表页)」，用户到了页面
 * 还得自己从几十行里找那一单，完全看不到「这单到底有什么问题、我该做什么」。
 * 这里改成：任何入口都只传 {scope, todoKey, bizType, bizNo, orderId}，由本服务查真实表，
 * 组装出「单据摘要 + 具体问题清单 + 业务时间线 + 当前角色可执行的动作」。</p>
 *
 * <p>三条硬原则：
 * 1. 所有字段来自真实业务表，不编造任何数字；
 * 2. 每条订单都过 OrderService.checkDataScope，越权数据直接不返回；
 * 3. actions 只描述「能做什么」，由前端按当前角色渲染，不在前端硬编码业务判断。</p>
 */
@Service
@RequiredArgsConstructor
public class WorkItemService {

    private final OrdersMapper ordersMapper;
    private final SettlementMapper settlementMapper;
    private final QualityReportMapper qualityMapper;
    private final DeliveryOrderMapper deliveryMapper;
    private final InboundRecordMapper inboundMapper;
    private final WarningMapper warningMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final ProductMapper productMapper;
    private final OrderService orderService;
    private final ConfigService configService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Map<Integer, String> STATUS = Map.ofEntries(
            Map.entry(10, "待接单"), Map.entry(20, "已接单"), Map.entry(30, "待质检"),
            Map.entry(40, "质检合格待配送"), Map.entry(41, "质检不合格已驳回"),
            Map.entry(50, "配送中"), Map.entry(60, "已送达待验收"),
            Map.entry(70, "已入库"), Map.entry(80, "待结算"),
            Map.entry(90, "已结算"), Map.entry(99, "已取消"), Map.entry(100, "已完成"));

    /** 统一入口：scope = notification / todo / drill */
    public Map<String, Object> build(String scope, String todoKey, String bizType,
                                     String bizNo, Long orderId, String keyword) {
        LoginUser me = UserContext.getOrNull();
        if (me == null) throw new BizException(401, "登录已失效");

        /* scope 大小写不敏感：前端传 'todo'，手工调接口常写成 'TODO'，
           落到 default 会把待办当成通知处理，返回一张「未找到单据」的空壳。 */
        String sc = scope == null ? "notification" : scope.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> items = switch (sc) {
            case "todo" -> todoItems(todoKey, me);
            case "drill" -> drillItems(keyword, me);
            default -> List.of(notificationItem(bizType, bizNo, orderId, me));
        };

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("scope", sc);
        res.put("role", me.getRole());
        res.put("total", items.size());
        res.put("items", items);
        return res;
    }

    // ------------------------------------------------------------ 待办：返回「具体哪几单」而不是一句汇总
    private List<Map<String, Object>> todoItems(String todoKey, LoginUser me) {
        if (!"ADMIN".equals(me.getRole())) return List.of();   // 看板待办目前是管理员视角
        String key = todoKey == null ? "" : todoKey;
        return switch (key) {
            case "SETTLE" -> settleTodos(me);
            case "OVERDUE" -> overdueTodos(me);
            case "CERT" -> warningTodos("证照", me);
            case "QUALITY" -> warningTodos("质检", me);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> settleTodos(LoginUser me) {
        double threshold = configService.getDouble("warn_loss_rate", 5);
        List<Settlement> list = settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStatus, "待审核")
                .orderByAsc(Settlement::getId).last("LIMIT 60"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Settlement s : list) {
            Orders o = s.getOrderId() == null ? null : ordersMapper.selectById(s.getOrderId());
            if (!visible(o, me)) continue;
            Map<String, Object> it = base("SETTLE-" + s.getId(), "结算单 " + s.getSettlementNo() + " 待审核",
                    bizSubtitle(o), "待审核", "warn");
            fields(it,
                    f("应结金额", money(s.getTotalAmount())),
                    f("损耗扣款", money(s.getLossDeduction())),
                    f("实结金额", money(s.getActualAmount())),
                    f("提交时间", time(s.getCreateTime())));

            List<Map<String, Object>> issues = new ArrayList<>();
            BigDecimal rate = inboundLossRate(s.getOrderId());
            if (rate != null) {
                issue(issues, rate.doubleValue() > threshold ? "danger" : "info",
                        rate.doubleValue() > threshold ? "损耗率超预警线" : "本单存在运输损耗",
                        "验收实秤损耗率 " + rate + "%，预警阈值 " + threshold + "%；扣款 " + money(s.getLossDeduction()),
                        rate + "%");
            }
            BigDecimal diff = (s.getTotalAmount() == null ? BigDecimal.ZERO : s.getTotalAmount())
                    .subtract(s.getActualAmount() == null ? BigDecimal.ZERO : s.getActualAmount());
            issue(issues, "info", "待确认结算差额",
                    "应结 " + money(s.getTotalAmount()) + " 与实结 " + money(s.getActualAmount())
                            + " 相差 " + money(diff) + "，审核通过后订单流转为已结算", money(diff));
            if (s.getRemark() != null && !s.getRemark().isBlank()) {
                issue(issues, "info", "结算备注", s.getRemark(), "备注");
            }
            it.put("issues", issues);
            it.put("timeline", orderTimeline(o));
            actions(it, me,
                    act("通过审核", "audit-pass", s.getId(), false),
                    act("驳回重核", "audit-reject", s.getId(), true),
                    routeAct("去结算审核页", "/admin/settlements", s.getSettlementNo()));
            out.add(it);
        }
        return out;
    }

    private List<Map<String, Object>> overdueTodos(LoginUser me) {
        int days = configService.getInt("warn_order_overdue_days", 2);
        List<Orders> list = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getStatus, 10)
                .lt(Orders::getCreateTime, LocalDateTime.now().minusDays(days))
                .orderByAsc(Orders::getCreateTime).last("LIMIT 60"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Orders o : list) {
            if (!visible(o, me)) continue;
            long overdue = Duration.between(o.getCreateTime(), LocalDateTime.now()).toDays();
            Map<String, Object> it = base("OVERDUE-" + o.getId(), "订单 " + o.getOrderNo() + " 逾期未发货",
                    bizSubtitle(o), STATUS.getOrDefault(o.getStatus(), "状态 " + o.getStatus()), "danger");
            fields(it, f("下单时间", time(o.getCreateTime())), f("约定送达", time(o.getExpectedDelivery())),
                    f("订购量", qty(o.getQuantity()) + " kg"), f("订单金额", money(o.getTotalAmount())));
            List<Map<String, Object>> issues = new ArrayList<>();
            issue(issues, "danger", "已逾期 " + overdue + " 天",
                    "订单创建于 " + time(o.getCreateTime()) + "，超过 " + days + " 天仍停留在「待接单」，"
                            + "需联系农户确认货源或取消订单", overdue + " 天");
            issue(issues, "info", "当前责任环节", "农户接单 · 可在订单监管页执行催办", "待接单");
            it.put("issues", issues);
            it.put("timeline", orderTimeline(o));
            actions(it, me, routeAct("去订单监管处理", "/admin/orders", o.getOrderNo()));
            out.add(it);
        }
        return out;
    }

    private List<Map<String, Object>> warningTodos(String type, LoginUser me) {
        List<Warning> list = warningMapper.selectList(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getStatus, "待处理")
                .like(Warning::getWarningType, type)
                .orderByDesc(Warning::getTriggerTime).last("LIMIT 60"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Warning w : list) {
            Map<String, Object> it = base("WARN-" + w.getId(), w.getWarningType() + " · " + w.getTarget(),
                    w.getContent(), w.getStatus(), "danger".equals(levelOf(w.getRiskLevel())) ? "danger" : "warn");
            fields(it, f("预警编号", w.getWarningNo()), f("风险等级", w.getRiskLevel()),
                    f("触发时间", time(w.getTriggerTime())), f("处置状态", w.getStatus()));
            List<Map<String, Object>> issues = new ArrayList<>();
            issue(issues, levelOf(w.getRiskLevel()), w.getWarningType() + " 待处置",
                    (w.getContent() == null ? "" : w.getContent()) + "；处置后需填写处理说明留痕", w.getRiskLevel());
            it.put("issues", issues);
            it.put("timeline", List.of(tl(time(w.getTriggerTime()), "预警触发", w.getContent())));
            actions(it, me,
                    act("标记已处置", "warning-handle", w.getId(), false),
                    /* 预警列表 keyword 只匹配 target/content，定位键用真实主体名而非编号 */
                    routeAct("去食安预警页", "/admin/warnings", w.getTarget()));
            out.add(it);
        }
        return out;
    }

    // ------------------------------------------------------------ KPI 明细行 → 定位到该行单据
    private List<Map<String, Object>> drillItems(String keyword, LoginUser me) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) return List.of();
        Orders o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getOrderNo, k).last("LIMIT 1"));
        if (o == null) {
            Settlement s = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                    .eq(Settlement::getSettlementNo, k).last("LIMIT 1"));
            if (s == null) return List.of();
            return List.of(notificationItem("SETTLE", k, s.getOrderId(), me));
        }
        return List.of(notificationItem("ORDER", o.getOrderNo(), o.getId(), me));
    }

    // ------------------------------------------------------------ 单条通知 → 该订单的完整上下文
    private Map<String, Object> notificationItem(String bizType, String bizNo, Long orderId, LoginUser me) {
        Orders o = null;
        if (orderId != null) o = ordersMapper.selectById(orderId);
        if (o == null && bizNo != null && !bizNo.isBlank()) {
            o = ordersMapper.selectOne(new LambdaQueryWrapper<Orders>()
                    .eq(Orders::getOrderNo, bizNo).last("LIMIT 1"));
            if (o == null) {
                Settlement s = settlementMapper.selectOne(new LambdaQueryWrapper<Settlement>()
                        .eq(Settlement::getSettlementNo, bizNo).last("LIMIT 1"));
                if (s != null && s.getOrderId() != null) o = ordersMapper.selectById(s.getOrderId());
            }
        }
        if (o == null) {
            Map<String, Object> empty = base("NONE", "业务单据 " + (bizNo == null ? "—" : bizNo),
                    "未找到关联的订单记录，可能已归档", "无数据", "info");
            empty.put("fields", List.of(f("业务类型", bizType == null ? "—" : bizType), f("业务单号", bizNo == null ? "—" : bizNo)));
            empty.put("issues", List.of(issueOne("info", "无法定位单据",
                    "请从对应业务列表页按单号检索；本工作台只展示真实存在的单据", "—")));
            empty.put("timeline", List.of());
            empty.put("actions", List.of());
            return empty;
        }
        if (!visible(o, me)) throw new BizException(403, "无权查看该单据");

        String type = bizType == null ? "ORDER" : bizType;
        Map<String, Object> it = base("ORDER-" + o.getId(), typeTitle(type) + " · " + o.getOrderNo(),
                bizSubtitle(o), STATUS.getOrDefault(o.getStatus(), "状态 " + o.getStatus()), levelOfStatus(o.getStatus()));
        fields(it,
                f("订单号", o.getOrderNo()), f("批次号", o.getBatchNo() == null ? "—" : o.getBatchNo()),
                f("订购量", qty(o.getQuantity()) + " kg"), f("订单金额", money(o.getTotalAmount())),
                f("当前状态", STATUS.getOrDefault(o.getStatus(), String.valueOf(o.getStatus()))),
                f("下单时间", time(o.getCreateTime())));
        it.put("issues", orderIssues(o, type));
        it.put("timeline", orderTimeline(o));
        it.put("actions", orderActions(o, type, me));
        return it;
    }

    /** 按订单真实数据算「具体问题」，这是用户说的「没有显示特定的问题」的核心 */
    private List<Map<String, Object>> orderIssues(Orders o, String type) {
        List<Map<String, Object>> issues = new ArrayList<>();
        double threshold = configService.getDouble("warn_loss_rate", 5);

        List<QualityReport> qr = qualityMapper.selectList(new LambdaQueryWrapper<QualityReport>()
                .eq(QualityReport::getOrderId, o.getId()).orderByDesc(QualityReport::getId));
        for (QualityReport q : qr) {
            boolean bad = q.getResult() != null && !q.getResult().contains("合格");
            issue(issues, bad ? "danger" : "ok", bad ? "质检不合格" : "质检合格",
                    "批次 " + q.getBatchNo() + " · 抽检 " + qty(q.getSampleQty()) + " kg · 结论 " + q.getResult()
                            + (q.getRemark() == null || q.getRemark().isBlank() ? "" : "；" + q.getRemark()),
                    q.getResult());
            if (bad) break;
        }

        List<InboundRecord> ibs = inboundMapper.selectList(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, o.getId()).orderByDesc(InboundRecord::getId));
        for (InboundRecord r : ibs) {
            BigDecimal rate = r.getLossRate();
            boolean over = rate != null && rate.doubleValue() > threshold;
            issue(issues, over ? "danger" : "info", over ? "损耗率超预警线" : "验收入库完成",
                    "下单 " + qty(r.getOrderQuantity()) + " kg，实收 " + qty(r.getReceiveQuantity())
                            + " kg，损耗 " + qty(r.getLossQuantity()) + " kg（" + rate + "%），阈值 " + threshold + "%"
                            + (r.getRemark() == null || r.getRemark().isBlank() ? "" : "；" + r.getRemark()),
                    rate + "%");
            break;
        }

        List<DeliveryOrder> ds = deliveryMapper.selectList(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, o.getId()).orderByDesc(DeliveryOrder::getId));
        for (DeliveryOrder d : ds) {
            BigDecimal t = d.getTemperature();
            if (t != null && (t.doubleValue() < 2 || t.doubleValue() > 8)) {
                issue(issues, "danger", "冷链温度异常",
                        "配送单 " + d.getDeliveryNo() + " 发车车厢温度 " + t + "℃，超出 2~8℃ 控制区间", t + "℃");
            } else if (t != null) {
                issue(issues, "ok", "冷链温度达标",
                        "配送单 " + d.getDeliveryNo() + " 车厢温度 " + t + "℃，在 2~8℃ 区间内", t + "℃");
            }
            break;
        }

        List<Settlement> sts = settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getOrderId, o.getId()).orderByDesc(Settlement::getId));
        for (Settlement s : sts) {
            String st = s.getStatus();
            issue(issues, "待审核".equals(st) ? "warn" : "ok",
                    "待审核".equals(st) ? "结算单待审核" : "结算单" + st,
                    "结算单 " + s.getSettlementNo() + " · 应结 " + money(s.getTotalAmount())
                            + "，损耗扣款 " + money(s.getLossDeduction()) + "，实结 " + money(s.getActualAmount()),
                    money(s.getActualAmount()));
            break;
        }

        if (o.getStatus() != null && o.getStatus() == 10) {
            long days = Duration.between(o.getCreateTime(), LocalDateTime.now()).toDays();
            if (days >= 2) issue(issues, "danger", "订单待接单已 " + days + " 天",
                    "创建于 " + time(o.getCreateTime()) + "，尚未有农户接单，需催办或取消", days + " 天");
        }
        if (issues.isEmpty()) {
            issue(issues, "info", "当前无异常",
                    "该订单 " + STATUS.getOrDefault(o.getStatus(), String.valueOf(o.getStatus()))
                            + "，暂无超限、超期或待审核事项；下方时间线展示已发生的业务节点", "正常");
        }
        return issues;
    }

    private List<Object> orderActions(Orders o, String type, LoginUser me) {
        String role = me.getRole();
        List<Object> list = new ArrayList<>();
        switch (role == null ? "" : role) {
            case "ADMIN" -> {
                list.add(routeAct("去订单监管", "/admin/orders", o.getOrderNo()));
                list.add(routeAct("查看结算单", "/admin/settlements", o.getOrderNo()));
                list.add(routeAct("查看溯源记录", "/admin/trace", o.getBatchNo() == null ? o.getOrderNo() : o.getBatchNo()));
            }
            case "FARMER" -> {
                list.add(routeAct("查看我的订单", "/farmer/orders", o.getOrderNo()));
                list.add(routeAct("查看我的对账单", "/farmer/settlement", o.getOrderNo()));
            }
            case "PURCHASER" -> {
                list.add(routeAct("查看订单", "/purchaser/orders", o.getOrderNo()));
                if (o.getStatus() != null && (o.getStatus() == 60 || o.getStatus() == 50)) {
                    list.add(routeAct("去验收入库", "/purchaser/inbound", o.getOrderNo()));
                }
                list.add(routeAct("查看溯源", "/purchaser/trace", o.getBatchNo() == null ? o.getOrderNo() : o.getBatchNo()));
            }
            case "INSPECTOR" -> {
                if (o.getStatus() != null && o.getStatus() <= 40) {
                    list.add(routeAct("去质检录入", "/inspector/quality", o.getOrderNo()));
                }
                list.add(routeAct("查看配送任务", "/inspector/delivery", o.getOrderNo()));
                list.add(routeAct("查看我的任务", "/inspector/tasks", o.getOrderNo()));
            }
            default -> list.add(routeAct("查看订单", "/admin/orders", o.getOrderNo()));
        }
        return list;
    }

    private List<Map<String, Object>> orderTimeline(Orders o) {
        if (o == null) return List.of();
        List<Map<String, Object>> t = new ArrayList<>();
        t.add(tl(time(o.getCreateTime()), "订单创建", "下单 " + qty(o.getQuantity()) + " kg · " + money(o.getTotalAmount())));
        for (QualityReport q : qualityMapper.selectList(new LambdaQueryWrapper<QualityReport>()
                .eq(QualityReport::getOrderId, o.getId()).orderByAsc(QualityReport::getId))) {
            t.add(tl(time(q.getInspectTime()), "到货质检", q.getResult() + " · 批次 " + q.getBatchNo()));
        }
        for (DeliveryOrder d : deliveryMapper.selectList(new LambdaQueryWrapper<DeliveryOrder>()
                .eq(DeliveryOrder::getOrderId, o.getId()).orderByAsc(DeliveryOrder::getId))) {
            t.add(tl(time(d.getStartTime()), "发车配送",
                    d.getVehicle() + " / " + d.getDriver() + " · " + (d.getTemperature() == null ? "—" : d.getTemperature() + "℃")));
            if (d.getArriveTime() != null) t.add(tl(time(d.getArriveTime()), "扫码交付", "配送单 " + d.getDeliveryNo()));
        }
        for (InboundRecord r : inboundMapper.selectList(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, o.getId()).orderByAsc(InboundRecord::getId))) {
            t.add(tl(time(r.getInboundTime()), "验收入库",
                    "实收 " + qty(r.getReceiveQuantity()) + " kg · 损耗 " + qty(r.getLossQuantity()) + " kg"));
        }
        for (Settlement s : settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getOrderId, o.getId()).orderByAsc(Settlement::getId))) {
            t.add(tl(time(s.getCreateTime()), "生成结算单", s.getSettlementNo() + " · " + s.getStatus()
                    + " · 实结 " + money(s.getActualAmount())));
        }
        return t;
    }

    // ------------------------------------------------------------ 小工具
    private boolean visible(Orders o, LoginUser me) {
        if (o == null) return false;
        if ("ADMIN".equals(me.getRole())) return true;
        if ("FARMER".equals(me.getRole())) return Objects.equals(o.getFarmerId(), me.getFarmerId());
        if ("PURCHASER".equals(me.getRole())) return Objects.equals(o.getCanteenId(), me.getCanteenId());
        return true;   // 质检配送员按订单批次作业，可见全平台待检/在途单
    }

    private String bizSubtitle(Orders o) {
        if (o == null) return "—";
        Farmer f = o.getFarmerId() == null ? null : farmerMapper.selectById(o.getFarmerId());
        Canteen c = o.getCanteenId() == null ? null : canteenMapper.selectById(o.getCanteenId());
        Product p = o.getProductId() == null ? null : productMapper.selectById(o.getProductId());
        return (f == null ? "农户" : f.getName()) + " → " + (c == null ? "食堂" : c.getName())
                + " · " + (p == null ? "农产品" : p.getName());
    }

    private BigDecimal inboundLossRate(Long orderId) {
        if (orderId == null) return null;
        InboundRecord r = inboundMapper.selectOne(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, orderId).orderByDesc(InboundRecord::getId).last("LIMIT 1"));
        return r == null ? null : r.getLossRate();
    }

    private static Map<String, Object> base(String key, String title, String subtitle, String status, String level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        m.put("subtitle", subtitle == null ? "" : subtitle);
        m.put("status", status == null ? "—" : status);
        m.put("level", level);
        m.put("fields", new ArrayList<>());
        m.put("issues", new ArrayList<>());
        m.put("timeline", new ArrayList<>());
        m.put("actions", new ArrayList<>());
        return m;
    }

    @SafeVarargs
    private static void fields(Map<String, Object> it, Map<String, Object>... fs) {
        it.put("fields", Arrays.asList(fs));
    }

    private static Map<String, Object> f(String label, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value == null || value.isBlank() ? "—" : value);
        return m;
    }

    private static void issue(List<Map<String, Object>> issues, String level, String title, String detail, String metric) {
        issues.add(issueOne(level, title, detail, metric));
    }

    private static Map<String, Object> issueOne(String level, String title, String detail, String metric) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("title", title);
        m.put("detail", detail);
        m.put("metric", metric == null ? "—" : metric);
        return m;
    }

    @SafeVarargs
    private static void actions(Map<String, Object> it, LoginUser me, Object... as) {
        it.put("actions", Arrays.asList(as));
    }

    private static Map<String, Object> act(String label, String type, Long id, boolean danger) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("type", type);
        m.put("id", id);
        m.put("danger", danger);
        return m;
    }

    private static Map<String, Object> routeAct(String label, String route, String focus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("type", "route");
        m.put("route", route);
        m.put("focus", focus == null ? "" : focus);
        return m;
    }

    private static Map<String, Object> tl(String time, String label, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time);
        m.put("label", label);
        m.put("desc", desc == null ? "" : desc);
        return m;
    }

    private static String typeTitle(String type) {
        return switch (type) {
            case "SHIP" -> "新批次待质检";
            case "QUALITY" -> "质检结果";
            case "DEPART" -> "批次已发车";
            case "ARRIVE" -> "已送达待验收";
            case "INBOUND" -> "已入库待结算";
            case "SETTLE" -> "结算审核";
            case "ORDER_NEW" -> "新订单";
            default -> "订单动态";
        };
    }

    private static String levelOfStatus(int status) {
        if (status == 41 || status == 99) return "danger";
        if (status == 80 || status == 30 || status == 60) return "warn";
        if (status == 90 || status == 100) return "ok";
        return "info";
    }

    private static String levelOf(String risk) {
        if (risk == null) return "info";
        if (risk.contains("高") || risk.equalsIgnoreCase("HIGH")) return "danger";
        if (risk.contains("中") || risk.equalsIgnoreCase("MID")) return "warn";
        return "info";
    }

    private static String time(LocalDateTime t) {
        return t == null ? "—" : t.format(FMT);
    }

    private static String money(BigDecimal v) {
        return v == null ? "¥ 0.00" : "¥ " + v.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
    }

    private static String qty(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
