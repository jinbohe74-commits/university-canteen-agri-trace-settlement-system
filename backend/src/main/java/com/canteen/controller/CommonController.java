package com.canteen.controller;

import com.canteen.common.Result;
import com.canteen.common.UserContext;
import com.canteen.entity.Canteen;
import com.canteen.entity.Farmer;
import com.canteen.entity.Product;
import com.canteen.service.CanteenService;
import com.canteen.service.DeliveryService;
import com.canteen.service.FarmerService;
import com.canteen.service.ProductService;
import com.canteen.service.RefService;
import com.canteen.service.TraceService;
import com.canteen.service.WorkItemService;
import com.canteen.service.FlowService;
import com.canteen.service.TodoCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公共字典与基础数据
 */
@RestController
@RequestMapping("/api/common")
@RequiredArgsConstructor
public class CommonController {

    private final ProductService productService;
    private final CanteenService canteenService;
    private final RefService refService;
    private final FarmerService farmerService;
    private final TraceService traceService;
    private final DeliveryService deliveryService;
    private final WorkItemService workItemService;
    private final FlowService flowService;
    private final TodoCenterService todoCenterService;

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("time", LocalDateTime.now().toString());
        m.put("application", "canteen-trace");
        return Result.success(m);
    }

    @GetMapping("/products")
    public Result<List<Product>> products() {
        return Result.success(productService.listAll());
    }

    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> categories() {
        return Result.success(productService.categories());
    }

    @GetMapping("/canteens")
    public Result<List<Canteen>> canteens() {
        return Result.success(canteenService.listAll());
    }

    @GetMapping("/farmers")
    public Result<List<Farmer>> farmers() {
        return Result.success(farmerService.page(1, 200, null, null, "已认证", null).getRecords());
    }

    @GetMapping("/order-status")
    public Result<List<Map<String, Object>>> orderStatus() {
        return Result.success(refService.orderStatusDict());
    }

    /**
     * 公共溯源查询：四个角色共用同一组件，统一走 /api/common/trace。
     * 返回结构与 /api/admin/trace 一致（recent + 可选 trace），无需按角色切换接口。
     */
    @GetMapping("/trace")
    public Result<Map<String, Object>> trace(@RequestParam(required = false) String batchNo) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("recent", traceService.recent(8));
        if (StringUtils.hasText(batchNo)) {
            res.put("trace", traceService.getTrace(batchNo.trim()));
        }
        return Result.success(res);
    }

    /** 按订单查实时轨迹：订单详情抽屉里四个角色共用，权限在 DeliveryService.track 内校验 */
    @GetMapping("/orders/{orderId}/track")
    public Result<Map<String, Object>> orderTrack(@PathVariable Long orderId) {
        return Result.success(deliveryService.trackByOrder(orderId));
    }

    @GetMapping("/me")
    public Result<Object> me() {
        return Result.success(UserContext.get());
    }

    /**
     * 单据工作台：通知「去处理」/ 看板待办 / KPI 明细行的统一终点。
     * 返回该单据的真实摘要、具体问题清单、业务时间线与当前角色可执行动作，
     * 而不是把用户扔到一个还要自己找单号的列表页。
     */
    @GetMapping("/work-item")
    public Result<Map<String, Object>> workItem(
            @RequestParam(required = false, defaultValue = "notification") String scope,
            @RequestParam(required = false) String todoKey,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String bizNo,
            @RequestParam(required = false) Long orderId,
            @RequestParam(required = false) String keyword) {
        return Result.success(workItemService.build(scope, todoKey, bizType, bizNo, orderId, keyword));
    }

    /** 我的业务流程：当前角色的职责、五步主线（带真实待办数）、全链路位置、角色 FAQ */
    @GetMapping("/my-flow")
    public Result<Map<String, Object>> myFlow() {
        return Result.success(flowService.myFlow());
    }

    /** 待办中心：顶栏「待办」的数据源，按角色返回分组待办（每条指向真实单据） */
    @GetMapping("/todo-center")
    public Result<Map<String, Object>> todoCenter() {
        return Result.success(todoCenterService.build());
    }
}
