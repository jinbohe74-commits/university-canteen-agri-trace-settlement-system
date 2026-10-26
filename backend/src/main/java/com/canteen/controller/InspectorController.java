package com.canteen.controller;

import com.canteen.common.Result;
import com.canteen.dto.QualitySubmitDTO;
import com.canteen.service.DeliveryService;
import com.canteen.service.LogService;
import com.canteen.service.QualityService;
import com.canteen.service.TraceService;
import com.canteen.service.WarningService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质检配送员端接口
 */
@RestController
@RequestMapping("/api/inspector")
@RequiredArgsConstructor
public class InspectorController {

    private final QualityService qualityService;
    private final DeliveryService deliveryService;
    private final TraceService traceService;
    private final WarningService warningService;
    private final LogService logService;

    // ---------------------------------------------------------------- 我的任务
    @GetMapping("/tasks")
    public Result<Map<String, Object>> tasks(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return Result.success(qualityService.tasks(page, size));
    }

    // ---------------------------------------------------------------- 质检录入
    @GetMapping("/quality/{orderId}")
    public Result<Map<String, Object>> qualityDetail(@PathVariable Long orderId) {
        return Result.success(qualityService.detail(orderId));
    }

    @PostMapping("/quality")
    public Result<Map<String, Object>> submitQuality(@RequestBody QualitySubmitDTO dto) {
        Map<String, Object> res = qualityService.submit(dto);
        String msg = "合格".equals(dto.getResult())
                ? "质检通过，已生成溯源二维码并放行配送" : "已判定不合格，批次驳回并扣减农户信用";
        return Result.success(msg, res);
    }

    // ---------------------------------------------------------------- 配送任务
    @GetMapping("/deliveries")
    public Result<Map<String, Object>> deliveries() {
        return Result.success(deliveryService.tasks());
    }

    @GetMapping("/deliveries/{id}")
    public Result<Map<String, Object>> deliveryDetail(@PathVariable Long id) {
        return Result.success(deliveryService.detail(id));
    }

    @PostMapping("/deliveries/{id}/start")
    public Result<Void> startDelivery(@PathVariable Long id) {
        deliveryService.start(id);
        return Result.success("已开始冷链配送", null);
    }

    @PostMapping("/deliveries/{id}/arrive")
    public Result<Void> arrive(@PathVariable Long id) {
        deliveryService.arrive(id);
        return Result.success("已扫码交付，等待食堂确认入库", null);
    }

    @PutMapping("/deliveries/{id}/temperature")
    public Result<Void> temperature(@PathVariable Long id, @RequestParam BigDecimal value) {
        deliveryService.updateTemperature(id, value);
        return Result.success("车厢温度已更新", null);
    }

    // ---------------------------------------------------------------- 异常上报
    @PostMapping("/exceptions")
    public Result<Map<String, Object>> reportException(@RequestBody Map<String, String> body) {
        String type = body.getOrDefault("type", "配送异常");
        String content = body.getOrDefault("content", "");
        String target = body.getOrDefault("target", "—");
        String level = body.getOrDefault("level", "中");
        warningService.create(level, "逾期预警".equals(type) ? "逾期预警" : "配送异常".equals(type)
                ? "逾期预警" : "质检预警", target, "【" + type + "】" + content);
        logService.record("质检配送", "上报异常：" + type + " · " + content, "成功");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", type);
        res.put("content", content);
        res.put("message", "异常已上报，管理员将在食安预警中跟进处理");
        return Result.success("上报成功", res);
    }

    @GetMapping("/exceptions")
    public Result<Map<String, Object>> exceptions() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("types", new String[]{"质检异常", "温度异常", "配送异常", "其他异常"});
        res.put("levels", new String[]{"高", "中", "低"});
        return Result.success(res);
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
}
