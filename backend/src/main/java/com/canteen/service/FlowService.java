package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * 「我的业务流程」—— 第七轮整改的上手引导契约。
 *
 * <p>用户反馈"我有点使用不明白"。走查 31 个页面后发现：每页的副标题都讲清了自己干什么，
 * 但<b>没有任何一页告诉他"这在整条链上是第几步、做完之后谁接手、我今天该先做哪件"</b>。
 * 于是页面之间是孤岛，用户只能靠猜。</p>
 *
 * <p>本服务把四个角色各自的工作主线固化成 5 步，每步给出：<br>
 * 1) what —— 你要做的事（业务语言，不是功能名）；<br>
 * 2) count —— 此刻库里真实的待办笔数（与业务接口同源、同数据权限）；<br>
 * 3) path/cta —— 点一下直达对应页面；<br>
 * 4) next —— 做完之后由谁接手，形成闭环认知。</p>
 *
 * <p>硬原则：数字必须可被对应业务页复算；查不到就返回 0 并如实说明，不编造"3 步完成"。</p>
 */
@Service
@RequiredArgsConstructor
public class FlowService {

    private final SettlementMapper settlementMapper;
    private final WarningMapper warningMapper;
    private final OrdersMapper ordersMapper;
    private final FarmerMapper farmerMapper;
    private final SupplySourceMapper sourceMapper;
    private final PurchaseDemandMapper demandMapper;
    private final DemandQuoteMapper quoteMapper;
    private final EvaluationMapper evaluationMapper;

    private static final Map<String, String> ROLE_LABEL = Map.of(
            "ADMIN", "系统管理员", "FARMER", "农户 / 合作社", "PURCHASER", "食堂采购员", "INSPECTOR", "质检配送员");

    /** 全链路四棒：所有角色共用同一张图，只是高亮不同 */
    private static final String[][] PIPELINE = {
            {"supply", "① 农户供货", "农户 / 合作社", "发布货源、响应需求、报价"},
            {"purchase", "② 采购下单", "食堂采购员", "提需求、比价定标、下单"},
            {"inspect", "③ 质检配送", "质检配送员", "抽检判定、发车、冷链轨迹"},
            {"settle", "④ 入库结算", "采购员验收 → 管理员审核", "实秤入库、损耗扣款、结算"}
    };

    public Map<String, Object> myFlow() {
        LoginUser me = UserContext.getOrNull();
        String role = me == null || me.getRole() == null ? "ADMIN" : me.getRole();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("role", role);
        res.put("roleLabel", ROLE_LABEL.getOrDefault(role, role));
        res.put("userName", me == null ? "" : me.getRealName());
        res.put("pipeline", pipeline(role));
        res.put("myStage", myStage(role));
        res.put("tips", tips(role));

        List<Map<String, Object>> steps;
        switch (role) {
            case "FARMER" -> steps = farmerSteps(me);
            case "PURCHASER" -> steps = purchaserSteps(me);
            case "INSPECTOR" -> steps = inspectorSteps(me);
            default -> steps = adminSteps(me);
        }
        res.put("steps", steps);
        res.put("duty", duty(role));
        // 第一个"有待办"的步骤，前端提示"建议先做这件"
        res.put("firstActionable", steps.stream()
                .filter(s -> ((Number) s.get("count")).longValue() > 0)
                .map(s -> s.get("key")).findFirst().orElse(null));
        return res;
    }

    /* ============================ 管理员 ============================ */
    private List<Map<String, Object>> adminSteps(LoginUser me) {
        long audit = count(settlementMapper, new LambdaQueryWrapper<Settlement>().eq(Settlement::getStatus, "待审核"));
        long warn = count(warningMapper, new LambdaQueryWrapper<Warning>().eq(Warning::getStatus, "待处理"));
        long running = count(ordersMapper, new LambdaQueryWrapper<Orders>().in(Orders::getStatus, 30, 40, 50, 60));
        long farmerPending = count(farmerMapper, new LambdaQueryWrapper<Farmer>().eq(Farmer::getStatus, "待审核"));
        long todayOrders = ordersMapper.selectCount(new LambdaQueryWrapper<Orders>()
                .apply("DATE(create_time) = CURDATE()"));
        BigDecimal todayAmount = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                        .apply("DATE(create_time) = CURDATE() AND status NOT IN (41,99)"))
                .stream().map(o -> o.getTotalAmount() == null ? BigDecimal.ZERO : o.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> list = new ArrayList<>();
        list.add(step(1, "audit", "审核结算单",
                "农户的货已入库、结算单已生成，等你按损耗扣款规则确认实结金额",
                "通过后订单转为「已结算」，农户端立刻看到这笔钱到账",
                audit, "笔待审核", "/admin/settlements", "去审核", "农户收款，采购员可评价", true));
        list.add(step(2, "warning", "处置食安预警",
                "证照到期、质检不合格、价格异动、订单逾期四类风险，按风险等级逐条处置",
                "处置结果写入操作日志，质检类不合格会回写农户信用分",
                warn, "条待处理", "/admin/warnings", "去处置", "农户信用分与匹配排名随之变化", true));
        list.add(step(3, "order", "跟进在途订单",
                "待质检 / 待配送 / 配送中 / 待验收的单子卡住时，这里能催办并看卡在哪一环",
                "催办会定向通知到当前卡点责任人，不打扰其他人",
                running, "笔在途", "/admin/orders", "去监管", "卡点责任人收到催办通知", false));
        list.add(step(4, "farmer", "审核农户准入",
                "新农户入驻要先审资质与证照，未认证的农户不能上架货源",
                "通过即标记「已认证」，其货源进入智能匹配候选池",
                farmerPending, "户待审核", "/admin/farmers", "去审核", "农户可发布货源、参与报价", false));
        list.add(step(5, "dashboard", "看经营态势",
                "采购额、损耗率、合格率、供应商排名，以及今天该处理的待办汇总",
                "发现异常指标可以直接点开明细往下钻到具体订单",
                todayOrders, "笔今日新单", "/admin/dashboard", "看数据",
                "数据来自全平台真实业务表，非演示值", false));
        if (todayAmount != null) {
            list.get(4).put("footnote", "今日采购额 ¥" + todayAmount.setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return list;
    }

    /* ============================ 农户 ============================ */
    private List<Map<String, Object>> farmerSteps(LoginUser me) {
        Long fid = me == null ? null : me.getFarmerId();
        if (fid == null) return Collections.emptyList();

        long onSale = count(sourceMapper, new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, fid).eq(SupplySource::getStatus, "正常"));
        long soldOut = count(sourceMapper, new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, fid).eq(SupplySource::getStatus, "已售罄"));

        // 待我报价：处理中的需求里，我还没报过价的
        List<PurchaseDemand> open = demandMapper.selectList(new LambdaQueryWrapper<PurchaseDemand>()
                .eq(PurchaseDemand::getStatus, "处理中").last("LIMIT 200"));
        Set<Long> quoted = new HashSet<>();
        quoteMapper.selectList(new LambdaQueryWrapper<DemandQuote>().eq(DemandQuote::getFarmerId, fid))
                .forEach(q -> quoted.add(q.getDemandId()));
        long toQuote = open.stream().filter(d -> !quoted.contains(d.getId())).count();

        long toAccept = count(ordersMapper, new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, fid).eq(Orders::getStatus, 10));
        long toDeliver = count(ordersMapper, new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, fid).eq(Orders::getStatus, 20));
        long waitInspect = count(ordersMapper, new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, fid).eq(Orders::getStatus, 30));
        long settlePending = count(settlementMapper, new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getFarmerId, fid).eq(Settlement::getStatus, "待审核"));

        List<Map<String, Object>> list = new ArrayList<>();
        list.add(step(1, "source", "把今天能供的菜挂上去",
                "写清品类、可供数量、单价和可供日期；已售罄的记得补货或下架",
                "货源进入采购员的「货源大厅」与智能匹配候选池，才会被选中",
                onSale, "个在售", "/farmer/sources", "去维护货源",
                "采购员在货源大厅看到并可勾选下单", false));
        list.add(step(2, "demand", "去需求大厅报价",
                "食堂发出的采购需求都在这，报价后由采购员横向比价定标",
                "中标即自动生成采购单，你会收到定向通知",
                toQuote, "条待报价", "/farmer/demands", "去报价",
                "采购员比价定标，中标方收到采购订单", false));
        list.add(step(3, "accept", "确认接单",
                "采购单来了先确认接单，写清预计发货时间；不接要说明原因",
                "接单后订单进入「已接单」，等你发货",
                toAccept, "笔待接单", "/farmer/orders?status=10", "去接单",
                "你发货后 → 质检配送员接手抽检", true));
        list.add(step(4, "deliver", "备货发货",
                "按订单数量备货，发货时绑定批次号，批次号是后续溯源的主键",
                "订单进入「待质检」，质检员现场抽检并出具报告",
                toDeliver, "笔待发货", "/farmer/orders?status=20", "去发货",
                "质检员抽检 → 合格才允许配送", true));
        if (waitInspect > 0) {
            list.get(3).put("footnote", "另有 " + waitInspect + " 笔已发货，正在等质检员抽检，不用你操作");
        }
        list.add(step(5, "settlement", "查结算与收入",
                "每单的实秤数量、损耗扣款、实结金额都在这，可导出对账单",
                "管理员审核通过后金额到账，信用分同步更新",
                settlePending, "笔待审核结算", "/farmer/settlement", "看结算",
                "管理员审核 → 你收到货款", false));
        return list;
    }

    /* ============================ 采购员 ============================ */
    private List<Map<String, Object>> purchaserSteps(LoginUser me) {
        Long cid = me == null ? null : me.getCanteenId();
        if (cid == null) return Collections.emptyList();

        long demands = count(demandMapper, new LambdaQueryWrapper<PurchaseDemand>()
                .eq(PurchaseDemand::getCanteenId, cid).eq(PurchaseDemand::getStatus, "处理中"));
        long supply = count(sourceMapper, new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getStatus, "正常"));
        long running = count(ordersMapper, new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, cid).in(Orders::getStatus, 10, 20, 30, 40, 50));
        long toInbound = count(ordersMapper, new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, cid).eq(Orders::getStatus, 60));
        long toSettle = count(ordersMapper, new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, cid).in(Orders::getStatus, 70, 80));

        List<Orders> settled = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, cid).eq(Orders::getStatus, 90));
        Set<Long> evaluated = new HashSet<>();
        evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>().eq(Evaluation::getCanteenId, cid))
                .forEach(e -> evaluated.add(e.getOrderId()));
        long toEval = settled.stream().filter(o -> !evaluated.contains(o.getId())).count();

        List<Map<String, Object>> list = new ArrayList<>();
        list.add(step(1, "demand", "提出采购需求",
                "写清今天要买的品类、数量、期望价和到货时间，系统自动推送给匹配的农户",
                "农户报价后你横向比价，定标即生成采购单",
                demands, "条处理中", "/purchaser/demands", "去提需求",
                "农户收到需求并报价", false));
        list.add(step(2, "source", "选货源 / 智能匹配",
                "货源大厅按品类、价格、信用筛；懒得比就进智能匹配看五因子推荐",
                "勾选货源可直接生成采购单，不必等报价",
                supply, "个在售货源", "/purchaser/match", "去匹配",
                "选定后生成采购单，农户收到通知", false));
        list.add(step(3, "order", "跟单到货",
                "下单后看接单、发货、质检、配送各环节，卡住会自动催办",
                "配送中的单子可以看冷链实时轨迹和温度曲线",
                running, "笔在途", "/purchaser/orders", "去跟单",
                "到货后由你验收", false));
        list.add(step(4, "inbound", "实秤验收入库",
                "车到了当场过秤，录入实收数量，系统按损耗率自动算扣款与结算金额",
                "入库即生成结算单交管理员审核，同时库存自动增加",
                toInbound, "笔待验收", "/purchaser/inbound", "去验收",
                "管理员审核结算 → 农户收款", true));
        list.add(step(5, "evaluation", "评价本次供货",
                "按质量、新鲜度、配送、服务打分，评价直接进农户信用分与匹配权重",
                "评价是不可撤销的履约凭证，也是下次选供应商的依据",
                toEval, "笔待评价", "/purchaser/evaluation", "去评价",
                "农户信用分变化，影响下次匹配排名", false));
        if (toSettle > 0) {
            list.get(3).put("footnote", "另有 " + toSettle + " 笔已入库待管理员审核结算");
        }
        return list;
    }

    /* ============================ 质检配送员 ============================ */
    private List<Map<String, Object>> inspectorSteps(LoginUser me) {
        long toInspect = count(ordersMapper, new LambdaQueryWrapper<Orders>().eq(Orders::getStatus, 30));
        long toShip = count(ordersMapper, new LambdaQueryWrapper<Orders>().eq(Orders::getStatus, 40));
        long shipping = count(ordersMapper, new LambdaQueryWrapper<Orders>().eq(Orders::getStatus, 50));
        long failed = count(ordersMapper, new LambdaQueryWrapper<Orders>().eq(Orders::getStatus, 41));
        long warn = count(warningMapper, new LambdaQueryWrapper<Warning>()
                .eq(Warning::getStatus, "待处理").eq(Warning::getWarningType, "质检预警"));

        List<Map<String, Object>> list = new ArrayList<>();
        list.add(step(1, "task", "领质检任务",
                "农户发货后单子进入「待质检」，这里是你今天要检的批次清单",
                "取一批就进入质检录入，按标准逐项填检测值",
                toInspect, "批待质检", "/inspector/tasks", "去领任务",
                "你录入结论 → 决定这批货能不能进食堂", true));
        list.add(step(2, "quality", "录入质检结论",
                "按农残、重金属、感官等标准项填实测值，系统自动判定合格与否并存证哈希",
                "合格生成溯源二维码与报告；不合格整批驳回并触发食安预警",
                failed, "批不合格待复检", "/inspector/quality", "去质检",
                "合格 → 进入配送环节；不合格 → 管理员处置", true));
        list.add(step(3, "delivery", "发车配送",
                "质检合格的单子安排车辆司机，发车时记录车牌与冷链起点温度",
                "订单转「配送中」，采购员可实时看到轨迹与温度",
                toShip, "批待发车", "/inspector/delivery", "去发车",
                "食堂采购员准备验收", true));
        list.add(step(4, "track", "在途跟踪与交付",
                "看冷链温度是否超标、路线是否偏离；到食堂后扫码交付",
                "交付后订单转「已送达」，采购员实秤入库",
                shipping, "批配送中", "/inspector/delivery", "看在途",
                "采购员验收入库", false));
        list.add(step(5, "exception", "异常上报",
                "路上温度超标、破损、迟到，或现场发现质量问题，当场登记并入预警",
                "异常会定向通知采购员与管理员，进入食安预警处置流程",
                warn, "条质检预警待处置", "/inspector/exception", "去上报",
                "管理员在预警页处置并留痕", false));
        return list;
    }

    /* ============================ 公共部分 ============================ */
    private List<Map<String, Object>> pipeline(String role) {
        String mine = myStage(role);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] p : PIPELINE) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p[0]);
            m.put("label", p[1]);
            m.put("who", p[2]);
            m.put("desc", p[3]);
            m.put("mine", p[0].equals(mine));
            out.add(m);
        }
        return out;
    }

    private String myStage(String role) {
        return switch (role) {
            case "FARMER" -> "supply";
            case "INSPECTOR" -> "inspect";
            case "PURCHASER" -> "purchase";
            default -> "settle";
        };
    }

    private String duty(String role) {
        return switch (role) {
            case "FARMER" -> "你负责把货供上来：维护货源、响应需求、接单发货，货进食堂后等结算收款。";
            case "PURCHASER" -> "你负责把菜买回来：提需求、选供应商、验收入库，最后一单评价决定农户下次能不能被优先匹配。";
            case "INSPECTOR" -> "你负责货能不能进食堂：抽检判定、发车配送、冷链监控，不合格就整批拦下。";
            default -> "你负责让这条链转得合规：审结算、处置预警、盯在途、审准入，并看住全平台经营指标。";
        };
    }

    /** 角色相关 FAQ：只写系统里真实成立的规则，不写"请联系客服"这类废话 */
    private List<Map<String, Object>> tips(String role) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(tip("为什么有些页面我看不到？",
                "左侧菜单由角色决定：农户只有供货相关 7 页，采购员只有采购协同 8 页。看不到 = 这块不归你负责，不是权限出错。"));
        list.add(tip("为什么有的单子我看不到？",
                "数据也按角色隔离：农户只看自己的订单，采购员只看本食堂，管理员看全平台。别人的单子点进来会被拦成 403。"));
        list.add(tip("点通知 / 待办之后会怎样？",
                "会直接打开这张单的「具体问题 + 业务时间线 + 你能做的动作」，不用再去列表里翻。"));
        list.add(tip("做完一步，怎么知道对方收到了？",
                "顶栏「通知」是定向的：只发给与该单相关的人。你提交后，下一棒的人会立刻收到一条带单号的提醒。"));
        if ("INSPECTOR".equals(role)) {
            list.add(tip("质检不合格会怎样？",
                    "整批驳回、订单转「已驳回」，同时生成食安预警交给管理员处置，农户信用分扣减。"));
        }
        if ("PURCHASER".equals(role)) {
            list.add(tip("验收数量少于订单数量，钱怎么算？",
                    "按实秤数量结算，差额自动按损耗率扣款，结算单上会写清「应结 / 扣款 / 实结」三行。"));
        }
        return list;
    }

    private static Map<String, Object> tip(String q, String a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("q", q);
        m.put("a", a);
        return m;
    }

    private static Map<String, Object> step(int no, String key, String title, String what, String result,
                                            long count, String unit, String path, String cta,
                                            String next, boolean blocking) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", no);
        m.put("key", key);
        m.put("title", title);
        m.put("what", what);
        m.put("result", result);
        m.put("count", count);
        m.put("unit", unit);
        m.put("path", path);
        m.put("cta", cta);
        m.put("next", next);
        // blocking = 这一步不做，下一棒的人就被卡住；只有它为 true 且真的有待办时才标红，
        // 否则一屏 5 步全红，等于没有优先级。
        m.put("urgent", blocking && count > 0);
        m.put("countText", count > 0 ? count + " " + unit : "暂无待办");
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static long count(com.baomidou.mybatisplus.core.mapper.BaseMapper mapper,
                             com.baomidou.mybatisplus.core.conditions.Wrapper w) {
        Long n = mapper.selectCount(w);
        return n == null ? 0L : n;
    }
}
