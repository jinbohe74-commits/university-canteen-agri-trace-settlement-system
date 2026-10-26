package com.canteen.controller;

import com.canteen.common.Result;
import com.canteen.common.UserContext;
import com.canteen.entity.Canteen;
import com.canteen.entity.Farmer;
import com.canteen.entity.Product;
import com.canteen.service.CanteenService;
import com.canteen.service.FarmerService;
import com.canteen.service.ProductService;
import com.canteen.service.RefService;
import com.canteen.service.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/trace")
    public Result<Map<String, Object>> trace(@RequestParam String batchNo) {
        return Result.success(java.util.Map.of("trace", traceService.getTrace(batchNo)));
    }

    @GetMapping("/me")
    public Result<Object> me() {
        return Result.success(UserContext.get());
    }
}
