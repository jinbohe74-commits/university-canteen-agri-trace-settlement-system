package com.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.PageResult;
import com.canteen.common.Result;
import com.canteen.entity.*;
import com.canteen.mapper.OperationLogMapper;
import com.canteen.mapper.QualityReportMapper;
import com.canteen.mapper.StatMapper;
import com.canteen.service.*;
import com.canteen.vo.OrderVO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 系统管理员端接口
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DashboardService dashboardService;
    private final FarmerService farmerService;
    private final CanteenService canteenService;
    private final ProductService productService;
    private final UserService userService;
    private final OrderService orderService;
    private final SettlementService settlementService;
    private final WarningService warningService;
    private final ConfigService configService;
    private final TraceService traceService;
    private final StatMapper statMapper;
    private final QualityReportMapper qualityReportMapper;
    private final OperationLogMapper logMapper;
    private final LogService logService;

    // ---------------------------------------------------------------- 看板
    /**
     * @param priceProducts 行情对比的商品 id，逗号分隔（最多 4 个）；不传则取成交最活跃的两个
     * @param priceDays     行情统计窗口天数（7 / 30 / 90 / 180），默认 30
     */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(
            @RequestParam(required = false) String priceProducts,
            @RequestParam(required = false) Integer priceDays) {
        return Result.success(dashboardService.adminDashboard(priceProducts, priceDays));
    }

    /** 看板「质检合格率」的明细依据：真实质检报告流水，供指标抽屉下钻 */
    @GetMapping("/quality-reports")
    public Result<List<Map<String, Object>>> qualityReports(
            @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        List<QualityReport> list = qualityReportMapper.selectList(
                new LambdaQueryWrapper<QualityReport>()
                        .orderByDesc(QualityReport::getId).last("LIMIT " + n));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QualityReport q : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("orderId", q.getOrderId());
            m.put("batchNo", q.getBatchNo());
            m.put("result", q.getResult());
            m.put("inspector", q.getInspector());
            m.put("sampleQty", q.getSampleQty());
            m.put("inspectTime", q.getInspectTime());
            m.put("remark", q.getRemark());
            rows.add(m);
        }
        return Result.success(rows);
    }

    // ---------------------------------------------------------------- 农户
    @GetMapping("/farmers")
    public Result<Map<String, Object>> farmers(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String creditLevel) {
        Map<String, Object> res = new LinkedHashMap<>();
        PageResult<Farmer> pr = farmerService.page(page, size, keyword, type, status, creditLevel);
        res.put("page", pr);
        res.put("stats", farmerService.stats());
        return Result.success(res);
    }

    @GetMapping("/farmers/{id}")
    public Result<Farmer> farmer(@PathVariable Long id) {
        return Result.success(farmerService.detail(id));
    }

    @PostMapping("/farmers")
    public Result<Farmer> saveFarmer(@RequestBody Farmer f) {
        return Result.success("保存成功", farmerService.save(f));
    }

    @PutMapping("/farmers/{id}")
    public Result<Farmer> updateFarmer(@PathVariable Long id, @RequestBody Farmer f) {
        f.setId(id);
        return Result.success("保存成功", farmerService.save(f));
    }

    @PutMapping("/farmers/{id}/status")
    public Result<Void> farmerStatus(@PathVariable Long id, @RequestParam String status) {
        farmerService.changeStatus(id, status);
        return Result.success();
    }

    // ---------------------------------------------------------------- 食堂
    @GetMapping("/canteens")
    public Result<Map<String, Object>> canteens(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String campus,
                                                @RequestParam(required = false) String status) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", canteenService.page(page, size, keyword, campus, status));
        res.put("stats", canteenService.stats());
        return Result.success(res);
    }

    @PostMapping("/canteens")
    public Result<Canteen> saveCanteen(@RequestBody Canteen c) {
        return Result.success("保存成功", canteenService.save(c));
    }

    @PutMapping("/canteens/{id}")
    public Result<Canteen> updateCanteen(@PathVariable Long id, @RequestBody Canteen c) {
        c.setId(id);
        return Result.success("保存成功", canteenService.save(c));
    }

    // ---------------------------------------------------------------- 农产品
    @GetMapping("/products")
    public Result<Map<String, Object>> products(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String status) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", productService.page(page, size, keyword, category, status));
        res.put("stats", productService.stats());
        return Result.success(res);
    }

    @PostMapping("/products")
    public Result<Product> saveProduct(@RequestBody Product p) {
        return Result.success("保存成功", productService.save(p));
    }

    @PutMapping("/products/{id}")
    public Result<Product> updateProduct(@PathVariable Long id, @RequestBody Product p) {
        p.setId(id);
        return Result.success("保存成功", productService.save(p));
    }

    @GetMapping("/products/{id}/price-history")
    public Result<List<Map<String, Object>>> priceHistory(@PathVariable Long id) {
        return Result.success(productService.priceHistory(id));
    }

    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> categories() {
        return Result.success(productService.categories());
    }

    // ---------------------------------------------------------------- 用户与权限
    @GetMapping("/users")
    public Result<Map<String, Object>> users(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String role) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", userService.page(page, size, keyword, role));
        res.put("stats", userService.stats());
        return Result.success(res);
    }

    @GetMapping("/users/roles")
    public Result<Map<String, Object>> roles() {
        return Result.success(userService.roles());
    }

    /** 新建/改绑账号时可选业务档案：只列没被其他账号占用的农户或食堂。 */
    @GetMapping("/users/bindable")
    public Result<List<Map<String, Object>>> bindable(@RequestParam String roleType,
                                                      @RequestParam(required = false) Long userId) {
        return Result.success(userService.bindable(roleType, userId));
    }

    /** 生成一个符合密码策略的随机初始密码，供管理员"重新生成"按钮使用。 */
    @GetMapping("/users/password-suggestion")
    public Result<Map<String, Object>> passwordSuggestion() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("password", userService.generatePassword());
        return Result.success(res);
    }

    @PostMapping("/users")
    public Result<Map<String, Object>> saveUser(@RequestBody Map<String, Object> body) {
        SysUser u = new SysUser();
        u.setId(body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id"))));
        u.setUsername((String) body.get("username"));
        u.setRealName((String) body.get("realName"));
        u.setPhone((String) body.get("phone"));
        u.setRoleType((String) body.get("roleType"));
        u.setStatus(body.get("status") == null ? 1 : Integer.valueOf(String.valueOf(body.get("status"))));
        Long roleId = body.get("roleId") == null ? null : Long.valueOf(String.valueOf(body.get("roleId")));
        Long farmerId = body.get("farmerId") == null ? null : Long.valueOf(String.valueOf(body.get("farmerId")));
        Long canteenId = body.get("canteenId") == null ? null : Long.valueOf(String.valueOf(body.get("canteenId")));
        return Result.success("保存成功", userService.save(u, (String) body.get("password"), roleId, farmerId, canteenId));
    }

    @PutMapping("/users/{id}/status")
    public Result<Void> userStatus(@PathVariable Long id, @RequestParam Integer status) {
        userService.changeStatus(id, status);
        return Result.success();
    }

    @PutMapping("/users/{id}/password")
    public Result<Map<String, Object>> resetPassword(@PathVariable Long id) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("password", userService.resetPassword(id));
        return Result.success("密码已重置，请立即保存新密码（仅展示一次）", res);
    }

    @PostMapping("/roles/{roleId}/menus")
    public Result<Void> saveRoleMenus(@PathVariable Long roleId, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) body.get("menuIds");
        List<Long> ids = raw == null ? List.of() : raw.stream().map(x -> Long.valueOf(String.valueOf(x))).toList();
        userService.saveRoleMenus(roleId, ids, (String) body.get("dataScope"));
        return Result.success();
    }

    // ---------------------------------------------------------------- 订单监管
    @GetMapping("/orders")
    public Result<Map<String, Object>> orders(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Long canteenId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer days) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", orderService.page(page, size, status, canteenId, null, keyword, days, "admin"));
        res.put("stats", orderStatistics());
        res.put("statusDict", orderService.statusMachine());
        return Result.success(res);
    }

    private Map<String, Object> orderStatistics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", statMapper.totalOrders());
        m.put("pending", statMapper.countByStatus(10));
        m.put("delivering", statMapper.countByStatus(50));
        m.put("pendingSettle", statMapper.countByStatus(80));
        m.put("today", statMapper.todayOrders());
        m.put("todayAmount", statMapper.todayAmount());
        m.put("statusCount", orderService.statistics("admin"));
        return m;
    }

    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> orderDetail(@PathVariable Long id) {
        return Result.success(orderService.detail(id));
    }

    @PostMapping("/orders/{id}/urge")
    public Result<Void> urge(@PathVariable Long id) {
        Orders o = orderService.getById(id);
        logService.record("订单管理", "催办订单 " + o.getOrderNo() + "，已通知农户尽快处理");
        return Result.success("已向农户发送催办通知", null);
    }

    @PostMapping("/orders/{id}/cancel")
    public Result<Void> cancelOrder(@PathVariable Long id, @RequestParam(required = false) String reason) {
        Orders o = orderService.getById(id);
        orderService.cancel(id, reason, o.getVersion());
        return Result.success();
    }

    // ---------------------------------------------------------------- 结算审核
    @GetMapping("/settlements")
    public Result<Map<String, Object>> settlements(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String period,
                                                   @RequestParam(required = false) String keyword) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", settlementService.page(page, size, status, period, keyword));
        res.put("stats", settlementService.stats());
        return Result.success(res);
    }

    @GetMapping("/settlements/{id}")
    public Result<Map<String, Object>> settlementDetail(@PathVariable Long id) {
        return Result.success(settlementService.detail(id));
    }

    @PostMapping("/settlements/audit")
    public Result<Map<String, Object>> audit(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) body.get("ids");
        List<Long> ids = raw == null ? List.of() : raw.stream().map(x -> Long.valueOf(String.valueOf(x))).toList();
        boolean pass = body.get("pass") == null || Boolean.parseBoolean(String.valueOf(body.get("pass")));
        return Result.success("审核完成", settlementService.audit(ids, pass, (String) body.get("remark")));
    }

    // ---------------------------------------------------------------- 食安预警
    @GetMapping("/warnings")
    public Result<Map<String, Object>> warnings(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int size,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String level,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String keyword) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", warningService.page(page, size, type, level, status, keyword));
        res.put("stats", warningService.stats());
        return Result.success(res);
    }

    @GetMapping("/warnings/{id}")
    public Result<Map<String, Object>> warningDetail(@PathVariable Long id) {
        return Result.success(warningService.detail(id));
    }

    @PostMapping("/warnings/{id}/handle")
    public Result<Void> handleWarning(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        warningService.handle(id, String.valueOf(body.getOrDefault("status", "已完成")),
                (String) body.get("remark"));
        return Result.success();
    }

    @PostMapping("/warnings/batch-handle")
    public Result<Map<String, Object>> batchHandle(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Object> raw = (List<Object>) body.get("ids");
        List<Long> ids = raw == null ? List.of() : raw.stream().map(x -> Long.valueOf(String.valueOf(x))).toList();
        return Result.success("批量处置完成",
                warningService.batchHandle(ids, String.valueOf(body.getOrDefault("status", "已完成"))));
    }

    // ---------------------------------------------------------------- 批次溯源
    @GetMapping("/trace")
    public Result<Map<String, Object>> trace(@RequestParam(required = false) String batchNo) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("recent", traceService.recent(8));
        if (StringUtils.hasText(batchNo)) {
            res.put("trace", traceService.getTrace(batchNo.trim()));
        }
        return Result.success(res);
    }

    // ---------------------------------------------------------------- 系统参数
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", configService.listAll());
        res.put("weights", configService.matchWeights());
        res.put("groups", List.of("MATCH", "CREDIT", "WARNING", "BUSINESS"));
        return Result.success(res);
    }

    @PutMapping("/config")
    public Result<Map<String, String>> saveConfig(@RequestBody Map<String, String> values) {
        configService.update(values);
        logService.record("参数配置", "保存系统参数：" + String.join("、", values.keySet()));
        return Result.success("配置已保存", configService.asMap());
    }

    @PostMapping("/config/reset")
    public Result<Void> resetConfig() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("match_weight_category", "0.20");
        defaults.put("match_weight_price", "0.25");
        defaults.put("match_weight_quantity", "0.15");
        defaults.put("match_weight_credit", "0.25");
        defaults.put("match_weight_distance", "0.15");
        defaults.put("credit_init_score", "80");
        defaults.put("credit_reward_normal", "1");
        defaults.put("credit_punish_late", "-3");
        defaults.put("credit_punish_quality", "-5");
        defaults.put("credit_punish_loss", "-2");
        defaults.put("warn_price_rate", "15");
        defaults.put("warn_order_overdue_days", "2");
        defaults.put("warn_cert_expire_days", "30");
        defaults.put("warn_loss_rate", "5");
        configService.update(defaults);
        logService.record("参数配置", "恢复系统参数默认值");
        return Result.success("已恢复默认配置", null);
    }

    // ---------------------------------------------------------------- 操作日志
    @GetMapping("/logs")
    public Result<Map<String, Object>> logs(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) String result,
                                            @RequestParam(required = false) String date) {
        LambdaQueryWrapper<OperationLog> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(OperationLog::getOperator, keyword).or().like(OperationLog::getContent, keyword));
        }
        if (StringUtils.hasText(module)) {
            qw.eq(OperationLog::getModule, module);
        }
        if (StringUtils.hasText(result)) {
            qw.eq(OperationLog::getResult, result);
        }
        if (StringUtils.hasText(date)) {
            qw.apply("DATE(create_time) = {0}", date);
        }
        // 按发生时间倒序：种子数据的 id 顺序与 create_time 不同序，按 id 排会让时间乱跳
        qw.orderByDesc(OperationLog::getCreateTime).orderByDesc(OperationLog::getId);
        IPage<OperationLog> p = logMapper.selectPage(new Page<>(page, size), qw);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("todayLogs", statMapper.todayLogs());
        stats.put("todayActive", statMapper.todayActiveUsers());
        stats.put("todayFailed", statMapper.todayFailedLogs());
        stats.put("total", statMapper.totalLogs());
        stats.put("failed", statMapper.failedLogs());
        stats.put("modules", statMapper.logModuleCount());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", PageResult.of(p));
        res.put("stats", stats);
        return Result.success(res);
    }

    @PostMapping("/logs/audit-policy")
    public Result<Void> auditPolicy(HttpServletRequest request) {
        logService.record("审计", "查看审计策略说明");
        return Result.success("审计策略：登录日志与业务操作日志全量留存，保留 180 天", null);
    }
}
