package com.canteen.controller;

import com.canteen.common.LoginUser;
import com.canteen.common.Result;
import com.canteen.common.UserContext;
import com.canteen.service.RealtimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时事件通道：ticket（Bearer 鉴权）→ SSE（一次性票据）→ 断线时轮询 /events 补漏。
 */
@RestController
@RequestMapping("/api/common/events")
@RequiredArgsConstructor
public class RealtimeController {

    private final RealtimeService realtime;

    @PostMapping("/ticket")
    public Result<Map<String, Object>> ticket() {
        LoginUser lu = UserContext.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticket", realtime.issueTicket(lu));
        m.put("cursor", realtime.maxId());
        return Result.success(m);
    }

    /** EventSource 无法携带 Authorization，改用一次性票据；此路径在 WebConfig 中排除拦截器 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String ticket) {
        return realtime.subscribe(ticket);
    }

    /** 轮询兜底：返回 since 之后对本用户可见的事件 */
    @GetMapping
    public Result<List<Map<String, Object>>> events(@RequestParam(defaultValue = "0") long since,
                                                    @RequestParam(defaultValue = "50") int limit) {
        LoginUser lu = UserContext.get();
        return Result.success(realtime.since(since, lu.getUserId(), lu.getRole(), limit));
    }

    /** 通知中心首屏 */
    @GetMapping("/recent")
    public Result<List<Map<String, Object>>> recent(@RequestParam(defaultValue = "20") int limit) {
        LoginUser lu = UserContext.get();
        return Result.success(realtime.recent(lu.getUserId(), lu.getRole(), limit));
    }
}
