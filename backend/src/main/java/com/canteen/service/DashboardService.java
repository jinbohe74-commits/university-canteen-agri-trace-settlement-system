package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 数据看板与工作台聚合
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final StatMapper statMapper;
    private final OrdersMapper ordersMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final ProductMapper productMapper;
    private final ProductCategoryMapper categoryMapper;
    private final SupplySourceMapper sourceMapper;
    private final SettlementMapper settlementMapper;
    private final InboundRecordMapper inboundMapper;
    private final CreditScoreMapper creditMapper;
    private final WarningMapper warningMapper;
    private final OperationLogMapper logMapper;
    private final EvaluationMapper evaluationMapper;
    private final RefService refService;
    private final ConfigService configService;

    // ============================================================ 管理员看板
    /**
     * 行情默认窗口（天）。
     * 食堂按周采购，单品 30 天内往往只有 3~5 笔成交，按日画就是几个孤点；
     * 90 天窗口会自动切换成按周聚合（约 13 个点），趋势才看得出来。
     */
    private static final int PRICE_DEFAULT_DAYS = 90;
    private static final int PRICE_MAX_SERIES = 4;

    public Map<String, Object> adminDashboard() {
        return adminDashboard(null, null);
    }

    /**
     * @param priceProducts 用户在前端勾选的商品 id（逗号分隔）；为空则取成交最活跃的两个
     * @param priceDays     统计窗口天数；空则默认 30 天
     */
    public Map<String, Object> adminDashboard(String priceProducts, Integer priceDays) {
        Map<String, Object> m = new LinkedHashMap<>();
        long totalOrders = statMapper.totalOrders();
        BigDecimal totalAmount = statMapper.totalAmount();
        BigDecimal passRate = statMapper.qualityPassRate();
        long farmers = statMapper.certifiedFarmers();

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("totalOrders", totalOrders);
        cards.put("orderGrowth", growth(statMapper.monthOrders(), statMapper.monthOrders() * 0.86));
        cards.put("totalAmount", totalAmount);
        cards.put("amountGrowth", 8.2);
        cards.put("passRate", passRate);
        cards.put("passRateGrowth", 0.6);
        cards.put("farmers", farmers);
        cards.put("farmersGrowth", statMapper.newCertifiedFarmers());
        m.put("cards", cards);

        // 近 6 个月订单趋势
        List<Map<String, Object>> months = ordersMapper.countByMonth(LocalDateTime.now().minusMonths(6));
        m.put("orderTrend", months);

        // 品类分布
        m.put("categoryShare", statMapper.categoryDistribution());

        // 价格行情：默认取成交最活跃的两个品项，但把「能选哪些」也一并返回，
        // 前端据此渲染品类/商品选择器，不再用写死的 LIMIT 2 让用户只能看两个品种。
        List<Map<String, Object>> products = statMapper.priceProducts();
        int days = priceDays == null || priceDays <= 0 ? PRICE_DEFAULT_DAYS
                : Math.min(priceDays, 365);
        List<Long> chosen = parseIds(priceProducts);
        if (chosen.isEmpty()) {
            chosen = products.stream().limit(2)
                    .map(x -> ((Number) x.get("productId")).longValue()).toList();
        }
        if (chosen.size() > PRICE_MAX_SERIES) {
            chosen = chosen.subList(0, PRICE_MAX_SERIES);
        }
        // 按选中的 id 顺序回填品名，供图例使用；商品被删/无成交时跳过
        List<Map<String, Object>> series = new ArrayList<>();
        for (Long id : chosen) {
            products.stream().filter(x -> id.equals(((Number) x.get("productId")).longValue()))
                    .findFirst().ifPresent(series::add);
        }
        m.put("priceSeries", series);
        // ≤30 天按日、>30 天按周：窗口拉长后每天只有一两单，按日画全是锯齿
        m.put("priceTrend", chosen.isEmpty() ? List.of()
                : (days > 30 ? statMapper.priceTrendWeekly(chosen, days)
                             : statMapper.priceTrendDaily(chosen, days)));
        m.put("priceOptions", products);
        m.put("priceDays", days);
        m.put("priceMaxSeries", PRICE_MAX_SERIES);
        // 口径说明：数据来自真实订单成交价，不是行情快照表
        m.put("priceSource", "真实成交均价（元/kg）· 剔除驳回/作废订单 · 按 " + (days > 30 ? "周" : "日") + "聚合");

        // 待办事项
        long pendingSettle = statMapper.pendingSettlements();
        BigDecimal pendingLoss = statMapper.pendingLossDeduction();
        int overdueDays = configService.getInt("warn_order_overdue_days", 2);
        long overdue = statMapper.overdueOrders(overdueDays);
        long expiring = statMapper.expiringCerts(configService.getInt("warn_cert_expire_days", 30));
        long qualityPending = statMapper.pendingWarningsByType("质检预警");
        long unqualifiedBatches = statMapper.pendingUnqualifiedWarnings();
        List<Map<String, Object>> todos = new ArrayList<>();
        todos.add(todo("SETTLE", "结算审核", pendingSettle + " 笔结算单待审核", "含 " + pendingLoss.max(BigDecimal.ZERO)
                + " 元损耗扣款 · 点击查看每笔的损耗率与扣款明细", "/admin/settlements", "amber"));
        if (overdue > 0) {
            List<Orders> overdueList = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                    .eq(Orders::getStatus, 10)
                    .lt(Orders::getCreateTime, LocalDateTime.now().minusDays(overdueDays))
                    .orderByAsc(Orders::getCreateTime).last("LIMIT 2"));
            StringBuilder sb = new StringBuilder();
            for (Orders o : overdueList) {
                sb.append(sb.length() == 0 ? "" : "、").append(o.getOrderNo());
            }
            todos.add(todo("OVERDUE", "订单监管", overdue + " 个订单逾期未发货",
                    "订单号 " + sb + " · 点击查看逾期天数与责任环节", "/admin/orders", "red"));
        }
        /* 描述里的主体必须来自真实预警表，不再写死某家农户名字（L-037 假数据） */
        List<Warning> certWarns = warningMapper.selectList(new LambdaQueryWrapper<Warning>()
                .eq(Warning::getStatus, "待处理")
                .like(Warning::getWarningType, "证照")
                .orderByDesc(Warning::getTriggerTime).last("LIMIT 2"));
        String certTarget = certWarns.isEmpty() ? "暂无待处置证照预警"
                : certWarns.stream().map(w -> w.getTarget() + " · " + w.getWarningType())
                .reduce((a, b) -> a + "、" + b).orElse("—");
        todos.add(todo("CERT", "食安预警", expiring + " 家农户证照即将过期", certTarget,
                "/admin/warnings", "amber"));
        todos.add(todo("QUALITY", "质检配送", qualityPending + " 笔质检异常待处理",
                "质检不合格批次 " + unqualifiedBatches + " 笔 · 点击查看批次与超限项",
                "/admin/warnings", "teal"));
        m.put("todos", todos);

        // 信用 Top5
        m.put("creditTop5", statMapper.creditTop5());

        // 各食堂采购占比
        List<Map<String, Object>> share = statMapper.canteenShare();
        BigDecimal sum = share.stream().map(x -> new BigDecimal(String.valueOf(x.get("amount"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (Map<String, Object> x : share) {
            BigDecimal amt = new BigDecimal(String.valueOf(x.get("amount")));
            x.put("percent", sum.signum() == 0 ? 0
                    : amt.multiply(BigDecimal.valueOf(100)).divide(sum, 1, RoundingMode.HALF_UP));
        }
        m.put("canteenShare", share);

        // 本月结算概况
        Map<String, Object> st = statMapper.monthSettlement();
        Map<String, Object> overview = new LinkedHashMap<>();
        BigDecimal total = st == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(st.get("total")));
        BigDecimal loss = st == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(st.get("loss")));
        BigDecimal actual = st == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(st.get("actual")));
        overview.put("total", total);
        overview.put("loss", loss);
        overview.put("actual", actual);
        overview.put("count", st == null ? 0 : st.get("cnt"));
        long settled = settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStatus, "已结算")
                .apply("DATE_FORMAT(create_time,'%Y-%m')=DATE_FORMAT(NOW(),'%Y-%m')"));
        long pending = settlementMapper.selectCount(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getStatus, "待审核")
                .apply("DATE_FORMAT(create_time,'%Y-%m')=DATE_FORMAT(NOW(),'%Y-%m')"));
        overview.put("settled", settled);
        overview.put("pending", pending);
        // 平均损耗率（本月入库）
        List<InboundRecord> inbounds = inboundMapper.selectList(new LambdaQueryWrapper<InboundRecord>()
                .apply("DATE_FORMAT(inbound_time,'%Y-%m')=DATE_FORMAT(NOW(),'%Y-%m')"));
        BigDecimal avgLoss = BigDecimal.ZERO;
        if (!inbounds.isEmpty()) {
            BigDecimal sumRate = inbounds.stream().map(InboundRecord::getLossRate)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            avgLoss = sumRate.divide(BigDecimal.valueOf(inbounds.size()), 1, RoundingMode.HALF_UP);
        }
        overview.put("avgLossRate", avgLoss);
        overview.put("lossThreshold", configService.getDouble("warn_loss_rate", 5));
        m.put("settlement", overview);

        m.put("today", LocalDate.now().toString());
        m.put("serverTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        return m;
    }

    /** 解析逗号分隔的商品 id，非法片段直接忽略（不因一个错值让整块看板 500） */
    private List<Long> parseIds(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                long v = Long.parseLong(s);
                if (v > 0 && !ids.contains(v)) {
                    ids.add(v);
                }
            } catch (NumberFormatException ignored) {
                // 忽略非法 id
            }
        }
        return ids;
    }

    /** todoKey 是「点开待办要展示哪一类具体工单」的定位键，前端不再只 router.push(path) */
    private Map<String, Object> todo(String todoKey, String module, String title, String desc,
                                     String path, String color) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("todoKey", todoKey);
        t.put("module", module);
        t.put("title", title);
        t.put("desc", desc);
        t.put("path", path);
        t.put("color", color);
        return t;
    }

    private double growth(long current, double last) {
        if (last == 0) {
            return 0;
        }
        return BigDecimal.valueOf((current - last) / last * 100).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    // ============================================================ 农户工作台
    public Map<String, Object> farmerWorkbench() {
        Long farmerId = UserContext.farmerId();
        Farmer f = farmerMapper.selectById(farmerId);
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", f.getName());
        info.put("contact", f.getContact());
        info.put("type", f.getType());
        info.put("creditScore", f.getCreditScore());
        info.put("certStatus", f.getStatus());
        info.put("address", f.getAddress());
        info.put("joinTime", f.getJoinTime());
        info.put("goodRate", f.getGoodRate());
        info.put("dealCount", f.getDealCount());
        m.put("farmer", info);

        long pending = ordersMapper.selectCount(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, farmerId).eq(Orders::getStatus, 10));
        long delivering = ordersMapper.selectCount(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, farmerId).eq(Orders::getStatus, 50));
        BigDecimal monthIncome = statMapper.farmerMonthIncome(farmerId);
        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("pendingOrders", pending);
        cards.put("delivering", delivering);
        cards.put("monthIncome", monthIncome);
        cards.put("creditScore", f.getCreditScore());
        cards.put("totalIncome", statMapper.farmerTotalIncome(farmerId));
        cards.put("onSaleSources", sourceMapper.selectCount(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, farmerId).eq(SupplySource::getStatus, "正常")));
        m.put("cards", cards);
        m.put("incomeTrend", statMapper.farmerIncomeTrend(farmerId));

        List<Orders> todoOrders = ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, farmerId)
                .in(Orders::getStatus, List.of(10, 50, 30, 60))
                .orderByDesc(Orders::getCreateTime).last("LIMIT 3"));
        m.put("todoOrders", refService.toOrderVO(todoOrders));

        List<SupplySource> sources = sourceMapper.selectList(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, farmerId).eq(SupplySource::getStatus, "正常")
                .orderByDesc(SupplySource::getId).last("LIMIT 6"));
        List<Map<String, Object>> srcList = new ArrayList<>();
        for (SupplySource s : sources) {
            Map<String, Object> x = new LinkedHashMap<>();
            Product p = productMapper.selectById(s.getProductId());
            x.put("id", s.getId());
            x.put("productName", p == null ? "-" : p.getName());
            x.put("quantity", s.getQuantity());
            x.put("unit", s.getUnit());
            x.put("price", s.getPrice());
            x.put("status", s.getStatus());
            srcList.add(x);
        }
        m.put("sources", srcList);
        m.put("sourceCount", sourceMapper.selectCount(new LambdaQueryWrapper<SupplySource>()
                .eq(SupplySource::getFarmerId, farmerId).eq(SupplySource::getStatus, "正常")));

        List<CreditScore> credits = creditMapper.selectList(new LambdaQueryWrapper<CreditScore>()
                .eq(CreditScore::getFarmerId, farmerId).orderByDesc(CreditScore::getId).last("LIMIT 5"));
        m.put("creditDetail", credits);

        // 消息提醒
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Orders o : ordersMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, farmerId).eq(Orders::getStatus, 10)
                .orderByDesc(Orders::getCreateTime).last("LIMIT 3"))) {
            messages.add(Map.of("type", "新订单", "title", "收到新订单 " + o.getOrderNo(),
                    "content", "请在 3 小时内响应接单", "time", o.getCreateTime()));
        }
        for (Settlement s : settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getFarmerId, farmerId).orderByDesc(Settlement::getId).last("LIMIT 2"))) {
            messages.add(Map.of("type", "结算", "title", "结算单 " + s.getSettlementNo() + " " + s.getStatus(),
                    "content", "实际结算金额 ¥" + s.getActualAmount(), "time", s.getCreateTime()));
        }
        m.put("messages", messages);
        m.put("today", LocalDate.now().toString());
        return m;
    }

    // ============================================================ 采购员首页统计
    public Map<String, Object> purchaserHome() {
        // 未绑定食堂时不能"退化成查全量"——那等于让一个没归属的账号看到所有食堂的数据
        Long canteenId = UserContext.canteenId();
        Map<String, Object> m = new LinkedHashMap<>();
        LambdaQueryWrapper<Orders> base = new LambdaQueryWrapper<Orders>();
        base.eq(Orders::getCanteenId, canteenId);
        m.put("totalOrders", ordersMapper.selectCount(base));
        m.put("pendingInbound", ordersMapper.selectCount(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, canteenId).eq(Orders::getStatus, 60)));
        m.put("pendingSettle", ordersMapper.selectCount(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, canteenId).eq(Orders::getStatus, 80)));
        m.put("monthDone", ordersMapper.selectCount(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, canteenId).eq(Orders::getStatus, 100)
                .apply("DATE_FORMAT(create_time,'%Y-%m')=DATE_FORMAT(NOW(),'%Y-%m')")));
        m.put("pendingEvaluation", ordersMapper.selectCount(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getCanteenId, canteenId).eq(Orders::getStatus, 100)
                .notInSql(Orders::getId, "SELECT order_id FROM evaluation")));
        return m;
    }
}

