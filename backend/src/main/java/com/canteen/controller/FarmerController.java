package com.canteen.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.PageResult;
import com.canteen.common.Result;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.service.*;
import com.canteen.vo.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 农户/合作社端接口
 */
@RestController
@RequestMapping("/api/farmer")
@RequiredArgsConstructor
public class FarmerController {

    private final DashboardService dashboardService;
    private final FarmerService farmerService;
    private final SourceService sourceService;
    private final OrderService orderService;
    private final SettlementService settlementService;
    private final TraceService traceService;
    private final CreditScoreMapper creditMapper;
    private final WarningMapper warningMapper;
    private final EvaluationMapper evaluationMapper;
    private final FarmerMapper farmerMapper;
    private final OrdersMapper orderMapper;
    private final SettlementMapper settlementMapper;
    private final QuoteService quoteService;

    /** 需求大厅：农户能看到本食堂仍在处理中、未过截止时间的采购需求并报价 */
    @GetMapping("/demand-hall")
    public Result<Map<String, Object>> demandHall(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "10") int size,
                                                  @RequestParam(required = false) String keyword) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", quoteService.demandHall(page, size, keyword));
        res.put("myQuotes", quoteService.myQuotes());
        return Result.success(res);
    }

    @GetMapping("/quotes")
    public Result<List<Map<String, Object>>> myQuotes() {
        return Result.success(quoteService.myQuotes());
    }

    @PostMapping("/quotes")
    public Result<com.canteen.entity.DemandQuote> submitQuote(
            @jakarta.validation.Valid @RequestBody com.canteen.dto.QuoteDTO dto) {
        com.canteen.entity.DemandQuote q = quoteService.submit(dto);
        return Result.success("报价已提交，等待采购员比价定标", q);
    }

    @GetMapping("/workbench")
    public Result<Map<String, Object>> workbench() {
        return Result.success(dashboardService.farmerWorkbench());
    }

    // ---------------------------------------------------------------- 货源
    @GetMapping("/sources")
    public Result<Map<String, Object>> sources(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", sourceService.myPage(page, size, keyword, status));
        res.put("stats", farmerService.sourceStats());
        return Result.success(res);
    }

    @PostMapping("/sources")
    public Result<SupplySource> createSource(@RequestBody SupplySource s) {
        s.setId(null);
        return Result.success("货源发布成功，已进入智能匹配候选池", sourceService.save(s));
    }

    @PutMapping("/sources/{id}")
    public Result<SupplySource> updateSource(@PathVariable Long id, @RequestBody SupplySource s) {
        s.setId(id);
        return Result.success("保存成功", sourceService.save(s));
    }

    @PutMapping("/sources/{id}/status")
    public Result<Void> sourceStatus(@PathVariable Long id, @RequestParam String status) {
        sourceService.changeStatus(id, status);
        return Result.success("状态已更新为 " + status, null);
    }

    // ---------------------------------------------------------------- 订单
    @GetMapping("/orders")
    public Result<Map<String, Object>> orders(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer days) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", orderService.page(page, size, status, null, null, keyword, days, "farmer"));
        res.put("stats", orderService.statistics("farmer"));
        return Result.success(res);
    }

    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> orderDetail(@PathVariable Long id) {
        return Result.success(orderService.detail(id));
    }

    @PostMapping("/orders/{id}/accept")
    public Result<Void> accept(@PathVariable Long id, @RequestParam(required = false) Integer version) {
        orderService.accept(id, version);
        return Result.success("接单成功，请按时安排采摘与发货", null);
    }

    @PostMapping("/orders/{id}/reject")
    public Result<Void> reject(@PathVariable Long id, @RequestParam(required = false) String reason,
                               @RequestParam(required = false) Integer version) {
        orderService.reject(id, reason, version);
        return Result.success("已拒单", null);
    }

    @PostMapping("/orders/{id}/ship")
    public Result<Void> ship(@PathVariable Long id, @RequestParam(required = false) Integer version) {
        orderService.ship(id, version);
        return Result.success("已确认发货，等待质检", null);
    }

    // ---------------------------------------------------------------- 结算与收入
    @GetMapping("/settlements")
    public Result<Map<String, Object>> settlements(@RequestParam(defaultValue = "1") int size,
                                                   @RequestParam(defaultValue = "10") int pageSize,
                                                   @RequestParam(required = false) String status) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", settlementService.farmerPage(size, pageSize, status));
        res.put("income", income());
        return Result.success(res);
    }

    @GetMapping("/income")
    public Result<Map<String, Object>> income() {
        Map<String, Object> m = new LinkedHashMap<>();
        Long fid = UserContext.farmerId();
        m.put("totalIncome", statIncome(fid));
        m.put("settledCount", statSettledCount(fid));
        m.put("pendingCount", settlementService.farmerPage(1, 100, "待审核").getTotal());
        m.put("monthIncome", dashboardService.farmerWorkbench().get("cards"));
        return Result.success(m);
    }

    private final StatMapper statMapper;

    private BigDecimal statIncome(Long fid) {
        BigDecimal v = statMapper.farmerTotalIncome(fid);
        return v == null ? BigDecimal.ZERO : v;
    }

    private long statSettledCount(Long fid) {
        return statMapper.farmerSettledCount(fid);
    }

    // ---------------------------------------------------------------- 信用评价
    @GetMapping("/credit")
    public Result<Map<String, Object>> credit() {
        Long fid = UserContext.farmerId();
        Farmer f = farmerMapper.selectById(fid);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("farmer", f);
        m.put("records", creditMapper.selectList(new LambdaQueryWrapper<CreditScore>()
                .eq(CreditScore::getFarmerId, fid)
                // 按发生时间倒序：按 id 排会与 create_time 不一致，用户看到的是乱序账本
                .orderByDesc(CreditScore::getCreateTime)
                .orderByDesc(CreditScore::getId)
                .last("LIMIT 30")));
        List<Evaluation> evals = evaluationMapper.selectList(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getFarmerId, fid).orderByDesc(Evaluation::getId).last("LIMIT 10"));
        m.put("evaluations", evals);
        if (!evals.isEmpty()) {
            BigDecimal sum = evals.stream().map(Evaluation::getOverallScore).reduce(BigDecimal.ZERO, BigDecimal::add);
            m.put("avgScore", sum.divide(BigDecimal.valueOf(evals.size()), 2, java.math.RoundingMode.HALF_UP));
        } else {
            m.put("avgScore", new BigDecimal("5.00"));
        }
        m.put("evalCount", evaluationMapper.selectCount(new LambdaQueryWrapper<Evaluation>()
                .eq(Evaluation::getFarmerId, fid)));
        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("履约时效", 94);
        dims.put("质检合格率", 96);
        dims.put("损耗控制", 90);
        dims.put("服务配合", 97);
        m.put("dimensions", dims);
        return Result.success(m);
    }

    // ---------------------------------------------------------------- 消息提醒
    @GetMapping("/messages")
    public Result<List<Map<String, Object>>> messages() {
        Long fid = UserContext.farmerId();
        List<Map<String, Object>> list = new ArrayList<>();
        // 1) 新订单 / 待发货提醒
        for (Orders o : orderMapper.selectList(new LambdaQueryWrapper<Orders>()
                .eq(Orders::getFarmerId, fid)
                .in(Orders::getStatus, List.of(10, 20))
                .orderByDesc(Orders::getCreateTime).last("LIMIT 6"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "新订单");
            m.put("level", o.getStatus() == 10 ? "高" : "中");
            m.put("title", "订单 " + o.getOrderNo() + (o.getStatus() == 10 ? " 待接单" : " 已接单，请按时发货"));
            m.put("content", "采购数量 " + o.getQuantity().stripTrailingZeros().toPlainString()
                    + "kg，订单金额 ¥" + o.getTotalAmount() + "，请及时处理以免逾期扣分");
            m.put("time", o.getCreateTime());
            m.put("status", o.getStatus() == 10 ? "待处理" : "进行中");
            list.add(m);
        }
        // 2) 结算通知
        for (Settlement s : settlementMapper.selectList(new LambdaQueryWrapper<Settlement>()
                .eq(Settlement::getFarmerId, fid)
                .orderByDesc(Settlement::getId).last("LIMIT 4"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "结算");
            m.put("level", "低");
            m.put("title", "结算单 " + s.getSettlementNo() + " " + s.getStatus());
            m.put("content", "订单金额 ¥" + s.getTotalAmount() + "，损耗扣款 ¥" + s.getLossDeduction()
                    + "，实际结算 ¥" + s.getActualAmount());
            m.put("time", s.getCreateTime());
            m.put("status", s.getStatus());
            list.add(m);
        }
        // 3) 食安与价格预警
        for (Warning w : warningMapper.selectList(new LambdaQueryWrapper<Warning>()
                .orderByDesc(Warning::getTriggerTime).last("LIMIT 6"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "预警");
            m.put("level", w.getRiskLevel());
            m.put("title", w.getContent());
            m.put("content", "预警对象：" + w.getTarget() + " · 状态：" + w.getStatus());
            m.put("time", w.getTriggerTime());
            m.put("status", w.getStatus());
            list.add(m);
        }
        list.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return Result.success(list);
    }

    // ---------------------------------------------------------------- 溯源
    @GetMapping("/trace")
    public Result<Map<String, Object>> trace(@RequestParam(required = false) String batchNo) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("recent", traceService.recent(8));
        if (batchNo != null && !batchNo.isBlank()) {
            res.put("trace", traceService.getTrace(batchNo.trim()));
        }
        return Result.success(res);
    }

    @GetMapping("/sources-all")
    public Result<List<Product>> products() {
        return Result.success(List.of());
    }
}

