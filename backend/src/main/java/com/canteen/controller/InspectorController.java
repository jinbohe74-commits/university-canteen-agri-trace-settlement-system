package com.canteen.controller;

import com.canteen.common.Result;
import com.canteen.dto.QualitySubmitDTO;
import com.canteen.mapper.StatMapper;
import com.canteen.service.DeliveryService;
import com.canteen.service.LogService;
import com.canteen.service.QualityService;
import com.canteen.service.TraceService;
import com.canteen.service.WarningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final StatMapper statMapper;

    // ---------------------------------------------------------------- 我的任务
    @GetMapping("/tasks")
    public Result<Map<String, Object>> tasks(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return Result.success(qualityService.tasks(page, size));
    }

    // ---------------------------------------------------------------- 质检录入
    /**
     * 质检报告逐批明细 —— 供「今日已检」「不合格」两个指标卡下钻。
     * 与统计口径严格一致：today=1 对应 DATE(inspect_time)=CURDATE()，unqualified=1 对应 result='不合格'。
     */
    @GetMapping("/quality-reports")
    public Result<Map<String, Object>> qualityReports(
            @RequestParam(defaultValue = "0") int today,
            @RequestParam(defaultValue = "0") int unqualified,
            @RequestParam(defaultValue = "100") int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", statMapper.qualityReportList(today == 1 ? 1 : 0, unqualified == 1 ? 1 : 0,
                Math.max(1, Math.min(limit, 300))));
        m.put("todayInspected", statMapper.todayInspected());
        m.put("unqualifiedTotal", statMapper.unqualifiedTotal());
        return Result.success(m);
    }

    @GetMapping("/quality/{orderId}")
    public Result<Map<String, Object>> qualityDetail(@PathVariable Long orderId) {
        return Result.success(qualityService.detail(orderId));
    }

    @PostMapping("/quality")
    public Result<Map<String, Object>> submitQuality(@Valid @RequestBody QualitySubmitDTO dto) {
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

    /**
     * 发车检查：确认车辆、司机与起始车厢温度。
     * body 可选，兼容旧的空调用（发车直接用派车信息 + 当前温度）。
     */
    @PostMapping("/deliveries/{id}/start")
    public Result<Map<String, Object>> startDelivery(@PathVariable Long id,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        String vehicle = body == null ? null : str(body.get("vehicle"));
        String driver = body == null ? null : str(body.get("driver"));
        BigDecimal temp = null;
        if (body != null && body.get("temperature") != null) {
            try {
                temp = new BigDecimal(String.valueOf(body.get("temperature")));
            } catch (NumberFormatException e) {
                return Result.error("起始温度必须是数值");
            }
        }
        return Result.success("已发车，冷链温度开始记录", deliveryService.start(id, vehicle, driver, temp));
    }

    /** 扫码交付：必须录对批次号后 6 位，等价于现场扫枪 */
    @PostMapping("/deliveries/{id}/arrive")
    public Result<Map<String, Object>> arrive(@PathVariable Long id,
                                              @RequestBody(required = false) Map<String, Object> body) {
        String code = body == null ? null : str(body.get("batchCode"));
        return Result.success("扫码核对通过，已交付", deliveryService.arrive(id, code));
    }

    /** 实时轨迹：位置、进度、ETA、温度曲线 */
    @GetMapping("/deliveries/{id}/track")
    public Result<Map<String, Object>> track(@PathVariable Long id) {
        return Result.success(deliveryService.track(id));
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    @PutMapping("/deliveries/{id}/temperature")
    public Result<Void> temperature(@PathVariable Long id, @RequestParam BigDecimal value) {
        deliveryService.updateTemperature(id, value);
        return Result.success("车厢温度已更新", null);
    }

    // ---------------------------------------------------------------- 异常上报
    @PostMapping("/exceptions")
    public Result<Map<String, Object>> reportException(@RequestBody Map<String, Object> body) {
        String type = body.get("type") == null ? "配送异常" : String.valueOf(body.get("type"));
        String content = body.get("content") == null ? "" : String.valueOf(body.get("content"));
        String target = body.get("target") == null || String.valueOf(body.get("target")).isBlank()
                ? "—" : String.valueOf(body.get("target"));
        String level = body.get("level") == null ? "中" : String.valueOf(body.get("level"));
        // 现场照片：前端已真实上传到 /api/files/upload，这里只收地址，和预警一起留痕
        List<String> photos = new ArrayList<>();
        if (body.get("photos") instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    photos.add(String.valueOf(o));
                }
            }
        }
        warningService.create(level, "逾期预警".equals(type) ? "逾期预警" : "配送异常".equals(type)
                ? "逾期预警" : "质检预警", target, "【" + type + "】" + content, photos);
        logService.record("质检配送", "上报异常：" + type + " · " + content
                + (photos.isEmpty() ? "" : " · 附照片 " + photos.size() + " 张"), "成功");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", type);
        res.put("content", content);
        res.put("photoCount", photos.size());
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
