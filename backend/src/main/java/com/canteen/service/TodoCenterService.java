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
 * 待办中心 —— 顶栏「待办」的数据源。
 *
 * <p>用户反馈「顶部栏待办这个功能形同虚设」：点一下只是 router.push 到某个列表页，
 * 徽标数字对农户/采购员读的是登录态里根本不存在的字段（恒 0），质检员干脆没有分支。
 * 也就是说它既不知道自己有几件事，也没告诉你具体是哪几件。</p>
 *
 * <p>这里按角色把待办拆成「组 → 条目」，每条都指向真实业务单据，并且：
 * 1. 计数与各业务列表页同源（同一张表、同一状态口径），不是另算一套；
 * 2. 标出「已等多久 / 逾期几天」，让用户能判断先后，而不是给一堆同样红的卡片；
 * 3. 明确区分「要你动手」和「只是让你知道」——只有前者计入徽标数字，
 *    否则质检员的"在途配送"也进待办，数字永远清不掉，用户就不看徽标了。</p>
 */
@Service
@RequiredArgsConstructor
public class TodoCenterService {

    private final OrdersMapper ordersMapper;
    private final SettlementMapper settlementMapper;
    private final WarningMapper warningMapper;
    private final PurchaseDemandMapper demandMapper;
    private final EvaluationMapper evaluationMapper;
    private final ProductMapper productMapper;
    private final FarmerMapper farmerMapper;
    private final InboundRecordMapper inboundMapper;
    private final ConfigService configService;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final Map<Integer, String> STATUS = Map.ofEntries(
            Map.entry(10, "待接单"), Map.entry(20, "已接单"), Map.entry(30, "待质检"),
            Map.entry(40, "质检合格待配送"), Map.entry(41, "质检不合格已驳回"),
            Map.entry(50, "配送中"), Map.entry(60, "已送达待验收"),
            Map.entry(70, "已入库"), Map.entry(80, "待结算"),
            Map.entry(90, "已结算"), Map.entry(99, "已取消"), Map.entry(100, "已完成"));

    public Map<String, Object> build() {
        LoginUser me = UserContext.getOrNull();
        if (me == null) throw new BizException(401, "登录已失效");
        String role = me.getRole() == null ? "" : me.getRole();

        List<Map<String, Object>> groups = switch (role) {
            case "ADMIN" -> adminGroups();
            case "FARMER" -> farmerGroups(me);
            case "PURCHASER" -> purchaserGroups(me);
            case "INSPECTOR" -> inspectorGroups();
            default -> List.of();
        };

        int total = 0;
        for (Map<String, Object> g : groups) {
            if (Boolean.TRUE.equals(g.get("actionable"))) total += ((Number) g.get("count")).intValue();
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("role", role);
        res.put("roleLabel", roleLabel(role));
        res.put("total", total);
        res.put("groups", groups);
        return res;
    }

    // ------------------------------------------------------------------ 管理员
    private List<Map<String, Object>> adminGroups() {
        List<Map<String, Object>> list = new ArrayList<>();

        // 结算单待审核：农户的钱卡在这一步，自己就是最后一关
        List<Settlement> sts = settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStatus, "待审核").orderByAsc(Settlement::getCreateTime).last("LIMIT 60"));
        List<Map<String, Object>> settleItems = new ArrayList<>();
        double threshold = configService.getDouble("warn_loss_rate", 5);
        for (Settlement s : sts) {
            Orders o = s.getOrderId() == null ? null : ordersMapper.selectById(s.getOrderId());
            Map<String, Object> it = base("SETTLE-" + s.getId(), "SETTLEMENT", s.getSettlementNo(),
                    "结算单 " + s.getSettlementNo(), "应结 " + money(s.getTotalAmount())
                            + " · 实结 " + money(s.getActualAmount())
                            + (o == null ? "" : " · " + orderTitle(o)));
            it.put("orderId", o == null ? null : o.getId());
            it.put("status", "待审核");
            BigDecimal rate = lossRateOf(s.getOrderId());
            boolean over = rate != null && rate.doubleValue() > threshold;
            it.put("level", over ? "danger" : "warn");
            it.put("note", over ? "损耗率 " + rate + "% 超预警线 " + threshold + "%" : "等待审核放款");
            it.put("ageText", waited(s.getCreateTime()));
            it.put("path", "/admin/settlements?focus=" + s.getSettlementNo());
            it.put("actionLabel", "去审核");
            settleItems.add(it);
        }
        list.add(group("SETTLE", "结算单待审核", "农户的钱等你放行；损耗超阈值会标红", true, settleItems));

        // 逾期未发货
        int days = configService.getInt("warn_order_overdue_days", 2);
        List<Orders> overdue = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getStatus, 10)
                .lt(Orders::getCreateTime, LocalDateTime.now().minusDays(days))
                .orderByAsc(Orders::getCreateTime).last("LIMIT 60"));
        List<Map<String, Object>> overdueItems = new ArrayList<>();
        for (Orders o : overdue) {
            long d = Duration.between(o.getCreateTime(), LocalDateTime.now()).toDays();
            Map<String, Object> it = orderBase(o, "ORDER", "催办或取消", "/admin/orders", "danger");
            it.put("title", "订单 " + o.getOrderNo() + " 逾期未接单");
            it.put("note", orderTitle(o) + " · 已逾期 " + d + " 天");
            it.put("ageText", "逾期 " + d + " 天");
            overdueItems.add(it);
        }
        list.add(group("OVERDUE", "订单逾期未接单", "超过 " + days + " 天无人接单，需要催办或取消", true, overdueItems));

        // 预警待处置
        List<Warning> warns = warningMapper.selectList(new LambdaQueryWrapper<Warning>()
                .in(Warning::getStatus, "待确认", "待处理").orderByDesc(Warning::getTriggerTime).last("LIMIT 60"));
        List<Map<String, Object>> warnItems = new ArrayList<>();
        for (Warning w : warns) {
            Map<String, Object> it = base("WARN-" + w.getId(), "WARNING", w.getWarningNo(),
                    w.getWarningType() + "预警 · " + (w.getTarget() == null ? "—" : w.getTarget()),
                    w.getContent() == null ? "" : w.getContent());
            it.put("status", w.getRiskLevel() == null ? "未处理" : w.getRiskLevel());
            it.put("level", "高".equals(w.getRiskLevel()) ? "danger" : "warn");
            it.put("note", w.getContent() == null ? "等待处置并留痕" : w.getContent());
            it.put("ageText", waited(w.getTriggerTime()));
            it.put("path", "/admin/warnings?focus=" + w.getWarningNo());
            it.put("actionLabel", "去处置");
            warnItems.add(it);
        }
        list.add(group("WARNING", "预警待处置", "食安 / 证照 / 信用异常，处置结果会留痕", true, warnItems));

        // 农户资质待审（不阻塞别人，但仍然是一件要做的事）
        List<Farmer> pending = farmerMapper.selectList(new LambdaQueryWrapper<Farmer>()
                .eq(Farmer::getStatus, "待审核").orderByAsc(Farmer::getId).last("LIMIT 60"));
        List<Map<String, Object>> farmerItems = new ArrayList<>();
        for (Farmer f : pending) {
            Map<String, Object> it = base("FARMER-" + f.getId(), "FARMER", String.valueOf(f.getId()),
                    "农户入驻待审核 · " + f.getName(),
                    (f.getContact() == null ? "" : f.getContact()) + " " + (f.getPhone() == null ? "" : f.getPhone()));
            it.put("status", "待审核");
            it.put("level", "info");
            it.put("note", "审核通过后即可发布货源、参与报价");
            it.put("ageText", "");
            it.put("path", "/admin/farmers?focus=" + (f.getFarmerNo() == null ? f.getId() : f.getFarmerNo()));
            it.put("actionLabel", "去审核");
            farmerItems.add(it);
        }
        list.add(group("FARMER", "农户入驻待审核", "通过后才能发布货源、参与报价", true, farmerItems));
        return list;
    }

    // ------------------------------------------------------------------ 农户
    private List<Map<String, Object>> farmerGroups(LoginUser me) {
        Long fid = me.getFarmerId();
        if (fid == null) throw new BizException(403, "当前账号未绑定农户档案");
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(group("ACCEPT", "待接单", "采购订单进来后要你确认，超时不接会影响信用", true,
                farmerOrders(fid, "10", "去接单", "/farmer/orders?status=10", "warn")));
        list.add(group("SHIP", "待发货", "已接单但还没发货，质检员在等这批货", true,
                farmerOrders(fid, "20", "去发货", "/farmer/orders?status=20", "warn")));

        // 只是让他知道，不需要动手 → 不计入徽标
        List<Map<String, Object>> waiting = farmerOrders(fid, "30", "看进度", "/farmer/orders?status=30", "info");
        for (Map<String, Object> it : waiting) {
            it.put("note", "已发货，等质检员抽检，这一步不需要你操作");
        }
        list.add(group("WAIT_QC", "等质检抽检", "你在等对方，不用动手", false, waiting));

        List<Map<String, Object>> money = new ArrayList<>();
        for (Map<String, Object> it : farmerOrders(fid, "80", "看对账", "/farmer/settlement", "warn")) {
            it.put("note", "入库完成，等管理员审核放款");
            money.add(it);
        }
        list.add(group("SETTLE", "待结算（等管理员放款）", "审核通过后到账", true, money));

        // 已结算的只是账目参考，不是待办
        List<Map<String, Object>> settled = farmerOrders(fid, "90", "看流水", "/farmer/settlement", "info");
        for (Map<String, Object> it : settled) {
            it.put("note", "已结算，可在对账单里查收款记录");
        }
        list.add(group("SETTLED", "已结算", "只做账目参考", false, settled));
        return list;
    }

    private List<Map<String, Object>> farmerOrders(Long farmerId, String status,
                                                   String action, String path, String level) {
        List<Orders> rows = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, farmerId)
                .eq(Orders::getStatus, Integer.parseInt(status))
                .orderByAsc(Orders::getCreateTime).last("LIMIT 60"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Orders o : rows) {
            Map<String, Object> it = orderBase(o, "ORDER", action, path, level);
            it.put("note", orderTitle(o) + " · 下单 " + fmt(o.getCreateTime()));
            it.put("ageText", waited(o.getCreateTime()));
            out.add(it);
        }
        return out;
    }

    // ------------------------------------------------------------------ 采购员
    private List<Map<String, Object>> purchaserGroups(LoginUser me) {
        Long cid = me.getCanteenId();
        if (cid == null) throw new BizException(403, "当前账号未绑定食堂档案");
        List<Map<String, Object>> list = new ArrayList<>();

        List<Orders> inbound = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, cid).eq(Orders::getStatus, 60)
                .orderByAsc(Orders::getCreateTime).last("LIMIT 60"));
        List<Map<String, Object>> inboundItems = new ArrayList<>();
        for (Orders o : inbound) {
            Map<String, Object> it = orderBase(o, "ORDER", "去入库", "/purchaser/inbound", "warn");
            it.put("note", orderTitle(o) + " · 已送达，等你实秤录入");
            it.put("ageText", waited(o.getUpdateTime() == null ? o.getCreateTime() : o.getUpdateTime()));
            inboundItems.add(it);
        }
        list.add(group("INBOUND", "待验收 / 入库", "货已送达，实秤录入后才生成结算单", true, inboundItems));

        // 在途：只需要知道，不需要动手
        List<Map<String, Object>> onway = new ArrayList<>();
        for (Integer st : new Integer[]{30, 40, 50}) {
            List<Orders> rows = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                    .eq(Orders::getCanteenId, cid).eq(Orders::getStatus, st)
                    .orderByAsc(Orders::getCreateTime).last("LIMIT 40"));
            for (Orders o : rows) {
                Map<String, Object> it = orderBase(o, "ORDER", "看进度", "/purchaser/orders", "info");
                it.put("note", orderTitle(o) + " · " + (st == 30 ? "待质检员抽检" : st == 40 ? "质检合格，等配送员发车" : "配送中"));
                it.put("ageText", waited(o.getUpdateTime() == null ? o.getCreateTime() : o.getUpdateTime()));
                onway.add(it);
            }
        }
        list.add(group("ONWAY", "在途订单", "质检 / 配送由对方推进，你只需知晓", false, onway));

        // 待评价：已完成但还没评价
        List<Orders> done = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, cid).eq(Orders::getStatus, 100)
                .orderByDesc(Orders::getUpdateTime).last("LIMIT 60"));
        List<Map<String, Object>> evalItems = new ArrayList<>();
        for (Orders o : done) {
            Long n = evaluationMapper.selectCount(new LambdaQueryWrapper<Evaluation>()
                    .eq(Evaluation::getOrderId, o.getId()));
            if (n != null && n > 0) continue;
            Map<String, Object> it = orderBase(o, "ORDER", "去评价", "/purchaser/evaluation", "info");
            it.put("note", orderTitle(o) + " · 未评价，评价会影响农户信用分");
            it.put("ageText", "");
            evalItems.add(it);
        }
        list.add(group("EVAL", "待评价", "评价影响农户信用分与下次匹配排名", true, evalItems));

        // 处理中的需求
        List<PurchaseDemand> demands = demandMapper.selectList(new LambdaQueryWrapper<PurchaseDemand>()
                .eq(PurchaseDemand::getCanteenId, cid).eq(PurchaseDemand::getStatus, "处理中")
                // 快到截止的排前面：报价截止一过，这条需求就废了，比"刚发的"更急
                .orderByAsc(PurchaseDemand::getDeadline).last("LIMIT 40"));
        List<Map<String, Object>> demandItems = new ArrayList<>();
        for (PurchaseDemand d : demands) {
            Product p = d.getProductId() == null ? null : productMapper.selectById(d.getProductId());
            Map<String, Object> it = base("DEMAND-" + d.getId(), "DEMAND", d.getDemandNo(),
                    "需求 " + d.getDemandNo() + " 报价中",
                    (p == null ? "—" : p.getName()) + " " + qty(d.getQuantity())
                            + " kg · 已收 " + (d.getQuoteCount() == null ? 0 : d.getQuoteCount()) + " 家报价");
            int quotes = d.getQuoteCount() == null ? 0 : d.getQuoteCount();
            it.put("status", d.getStatus());
            it.put("level", quotes == 0 ? "danger" : "info");
            it.put("note", quotes == 0
                    ? "还没有农户报价，必要时可调整期望价或撤回"
                    : "已收 " + quotes + " 家报价，可去比价定标");
            // 待办上最有用的信息是"还剩多少时间"，不是"创建了多久"
            it.put("ageText", untilDeadline(d.getDeadline()));
            // 只跳列表页等于让用户自己在几十行里找单号（第九轮同类投诉）；
            // 带上 focus，需求页会定位到这一条并直接打开比价面板。
            it.put("demandId", d.getId());
            it.put("demandNo", d.getDemandNo());
            it.put("path", "/purchaser/demands?focus=" + d.getDemandNo());
            it.put("actionLabel", "去比价");
            demandItems.add(it);
        }
        long noQuote = demandItems.stream().filter(x -> "danger".equals(x.get("level"))).count();
        list.add(group("DEMAND", "需求处理中",
                noQuote > 0 ? "其中 " + noQuote + " 条暂无农户报价，按报价截止时间排序"
                        : "按报价截止时间排序，到期前要定标",
                true, demandItems));
        return list;
    }

    // ------------------------------------------------------------------ 质检配送员
    private List<Map<String, Object>> inspectorGroups() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(group("INSPECT", "待质检", "货已发出，抽检结论决定能不能进食堂", true,
                inspectorOrders("30", "去质检", "/inspector/quality", "danger")));
        list.add(group("RELEASE", "质检合格待配送", "已放行，等你安排发车", true,
                inspectorOrders("40", "去发车", "/inspector/delivery", "warn")));
        list.add(group("SHIPPING", "配送中", "已发车，到货后采购员验收", false,
                inspectorOrders("50", "看轨迹", "/inspector/delivery", "info")));
        return list;
    }

    private List<Map<String, Object>> inspectorOrders(String status, String action, String path, String level) {
        List<Orders> rows = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getStatus, Integer.parseInt(status))
                .orderByAsc(Orders::getCreateTime).last("LIMIT 60"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Orders o : rows) {
            /* 待办点下去必须落到「这一单」，不能只把用户丢到列表页自己找（第九轮同类投诉）：
               配送任务页按 focus=订单号 定位，质检录入页按 orderId 直接带出该单 */
            String p = path;
            if (path.contains("/inspector/delivery")) {
                p = path + "?focus=" + o.getOrderNo();
            } else if (path.contains("/inspector/quality") && o.getId() != null) {
                p = path + "?orderId=" + o.getId();
            }
            Map<String, Object> it = orderBase(o, "ORDER", action, p, level);
            it.put("note", orderTitle(o) + (status.equals("30") ? " · 待抽检放行" : status.equals("40") ? " · 已放行待发车" : " · 在途"));
            it.put("ageText", waited(o.getUpdateTime() == null ? o.getCreateTime() : o.getUpdateTime()));
            out.add(it);
        }
        return out;
    }

    // ------------------------------------------------------------------ 复用工具
    private Map<String, Object> orderBase(Orders o, String bizType, String action, String path, String level) {
        Map<String, Object> it = base("ORDER-" + o.getId(), bizType, o.getOrderNo(),
                "订单 " + o.getOrderNo(), orderTitle(o));
        it.put("orderId", o.getId());
        it.put("status", STATUS.getOrDefault(o.getStatus(), "状态 " + o.getStatus()));
        it.put("level", level);
        it.put("path", withFocus(path, o.getOrderNo()));
        it.put("actionLabel", action);
        return it;
    }

    /** 落地页统一用 ?focus=<业务编号> 定位到具体那一条；这里补上，别让用户跳到列表里自己找 */
    private static String withFocus(String path, String no) {
        if (path == null || no == null || no.isBlank() || path.contains("focus=")) {
            return path;
        }
        return path + (path.contains("?") ? "&" : "?") + "focus=" + no;
    }

    private String orderTitle(Orders o) {
        Product p = o.getProductId() == null ? null : productMapper.selectById(o.getProductId());
        Farmer f = o.getFarmerId() == null ? null : farmerMapper.selectById(o.getFarmerId());
        return (p == null ? "—" : p.getName()) + " " + qty(o.getQuantity()) + " kg"
                + (f == null ? "" : " · " + f.getName());
    }

    private static Map<String, Object> group(String key, String label, String hint,
                                             boolean actionable, List<Map<String, Object>> items) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("key", key);
        g.put("label", label);
        g.put("hint", hint);
        g.put("actionable", actionable);
        g.put("count", items.size());
        g.put("items", items);
        return g;
    }

    private static Map<String, Object> base(String key, String bizType, String bizNo,
                                            String title, String subtitle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("bizType", bizType);
        m.put("bizNo", bizNo);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("orderId", null);
        m.put("note", "");
        m.put("ageText", "");
        return m;
    }

    private BigDecimal lossRateOf(Long orderId) {
        if (orderId == null) return null;
        InboundRecord r = inboundMapper.selectOne(new LambdaQueryWrapper<InboundRecord>()
                .eq(InboundRecord::getOrderId, orderId)
                .orderByDesc(InboundRecord::getId).last("LIMIT 1"));
        return r == null ? null : r.getLossRate();
    }

    /** 距报价截止还剩多久；已过期返回"已过截止 N 天" */
    private static String untilDeadline(LocalDateTime deadline) {
        if (deadline == null) return "";
        long min = Duration.between(LocalDateTime.now(), deadline).toMinutes();
        if (min < 0) return "已过截止 " + (-min / 60 / 24 + 1) + " 天";
        if (min < 60) return "剩 " + Math.max(1, min) + " 分钟";
        long h = min / 60;
        if (h < 24) return "剩 " + h + " 小时";
        return "剩 " + (h / 24) + " 天";
    }

    private static String waited(LocalDateTime t) {
        if (t == null) return "";
        long min = Duration.between(t, LocalDateTime.now()).toMinutes();
        if (min < 60) return "已等 " + Math.max(1, min) + " 分钟";
        long h = min / 60;
        if (h < 24) return "已等 " + h + " 小时";
        return "已等 " + (h / 24) + " 天";
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "—" : t.format(FMT);
    }

    private static String qty(BigDecimal v) {
        if (v == null) return "0";
        return v.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal v) {
        return "¥" + (v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case "ADMIN" -> "管理员";
            case "FARMER" -> "农户";
            case "PURCHASER" -> "采购员";
            case "INSPECTOR" -> "质检配送员";
            default -> role;
        };
    }
}
