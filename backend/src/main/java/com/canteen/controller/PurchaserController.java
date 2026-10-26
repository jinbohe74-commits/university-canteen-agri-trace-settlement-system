package com.canteen.controller;

import com.canteen.common.Result;
import com.canteen.dto.EvaluationDTO;
import com.canteen.dto.InboundDTO;
import com.canteen.entity.Orders;
import com.canteen.entity.PurchaseDemand;
import com.canteen.entity.SupplySource;
import com.canteen.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食堂采购员端接口
 */
@RestController
@RequestMapping("/api/purchaser")
@RequiredArgsConstructor
public class PurchaserController {

    private final DemandService demandService;
    private final SourceService sourceService;
    private final MatchService matchService;
    private final OrderService orderService;
    private final InboundService inboundService;
    private final EvaluationService evaluationService;
    private final TraceService traceService;
    private final SettlementService settlementService;

    // ---------------------------------------------------------------- 采购需求
    @GetMapping("/demands")
    public Result<Map<String, Object>> demands(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", demandService.page(page, size, keyword, status));
        res.put("stats", demandService.stats());
        return Result.success(res);
    }

    @PostMapping("/demands")
    public Result<PurchaseDemand> createDemand(@RequestBody PurchaseDemand d) {
        d.setId(null);
        return Result.success("需求发布成功，系统已启动智能匹配", demandService.save(d));
    }

    @PutMapping("/demands/{id}")
    public Result<PurchaseDemand> updateDemand(@PathVariable Long id, @RequestBody PurchaseDemand d) {
        d.setId(id);
        return Result.success("保存成功", demandService.save(d));
    }

    @PutMapping("/demands/{id}/withdraw")
    public Result<Void> withdraw(@PathVariable Long id) {
        demandService.withdraw(id);
        return Result.success("需求已撤回", null);
    }

    @GetMapping("/demands/{id}")
    public Result<Object> demand(@PathVariable Long id) {
        return Result.success(demandService.toVO(demandService.getById(id)));
    }

    // ---------------------------------------------------------------- 货源大厅
    @GetMapping("/sources")
    public Result<Map<String, Object>> sources(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int size,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) BigDecimal priceMax,
                                               @RequestParam(required = false) BigDecimal creditMin,
                                               @RequestParam(required = false) String sort) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", sourceService.hall(page, size, keyword, category, priceMax, creditMin, sort));
        return Result.success(res);
    }

    // ---------------------------------------------------------------- 智能匹配
    @GetMapping("/match/{demandId}")
    public Result<Map<String, Object>> match(@PathVariable Long demandId) {
        return Result.success(matchService.match(demandId));
    }

    // ---------------------------------------------------------------- 订单
    @GetMapping("/orders")
    public Result<Map<String, Object>> orders(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) Integer days) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", orderService.page(page, size, status, null, null, keyword, days, "purchaser"));
        res.put("stats", orderService.statistics("purchaser"));
        return Result.success(res);
    }

    @GetMapping("/orders/{id}")
    public Result<Map<String, Object>> orderDetail(@PathVariable Long id) {
        return Result.success(orderService.detail(id));
    }

    @PostMapping("/orders")
    public Result<Orders> createOrder(@RequestBody Map<String, Object> body) {
        Long demandId = body.get("demandId") == null ? null : Long.valueOf(String.valueOf(body.get("demandId")));
        Long sourceId = Long.valueOf(String.valueOf(body.get("sourceId")));
        BigDecimal quantity = new BigDecimal(String.valueOf(body.get("quantity")));
        LocalDateTime expected = body.get("expectedDelivery") == null ? null
                : LocalDateTime.parse(String.valueOf(body.get("expectedDelivery")));
        Orders o = orderService.create(demandId, sourceId, quantity, expected);
        return Result.success("下单成功，已通知农户接单", o);
    }

    // ---------------------------------------------------------------- 入库确认
    @GetMapping("/inbound")
    public Result<Map<String, Object>> inboundPreview(@RequestParam(required = false) String orderNo) {
        return Result.success(inboundService.preview(orderNo));
    }

    @PostMapping("/inbound")
    public Result<Map<String, Object>> inbound(@RequestBody InboundDTO dto) {
        return Result.success("入库成功，已自动生成结算单", inboundService.confirm(dto));
    }

    @GetMapping("/inbound/recent")
    public Result<List<Map<String, Object>>> inboundRecent(@RequestParam(defaultValue = "5") int limit) {
        return Result.success(inboundService.recent(limit));
    }

    // ---------------------------------------------------------------- 采购评价
    @GetMapping("/evaluations")
    public Result<Map<String, Object>> evaluations(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("pending", evaluationService.pending(page, size));
        res.put("history", evaluationService.history(page, size));
        res.put("tags", List.of("新鲜水灵", "分量足", "配送及时", "农残合格", "价格实惠", "包装完好", "服务热情", "品质稳定"));
        res.put("dimensions", List.of("产品品质", "新鲜程度", "配送时效", "服务态度", "包装与分量"));
        return Result.success(res);
    }

    @PostMapping("/evaluations")
    public Result<Void> submitEvaluation(@RequestBody EvaluationDTO dto) {
        evaluationService.submit(dto);
        return Result.success("评价已提交，结果已纳入农户信用", null);
    }

    // ---------------------------------------------------------------- 结算与溯源
    @GetMapping("/settlements")
    public Result<Map<String, Object>> settlements(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size,
                                                   @RequestParam(required = false) String status) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("page", settlementService.page(page, size, status, null, null));
        res.put("stats", settlementService.stats());
        return Result.success(res);
    }

    @GetMapping("/settlements/{id}")
    public Result<Map<String, Object>> settlementDetail(@PathVariable Long id) {
        return Result.success(settlementService.detail(id));
    }

    @GetMapping("/trace")
    public Result<Map<String, Object>> trace(@RequestParam(required = false) String batchNo) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("recent", traceService.recent(8));
        if (batchNo != null && !batchNo.isBlank()) {
            res.put("trace", traceService.getTrace(batchNo.trim()));
        }
        return Result.success(res);
    }
}
