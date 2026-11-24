package com.canteen.controller;

import com.canteen.common.BizException;
import com.canteen.common.Result;
import com.canteen.common.UserContext;
import com.canteen.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 定向通知：只返回当前登录人自己的消息 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/recent")
    public Result<Map<String, Object>> recent(@RequestParam(defaultValue = "50") int limit) {
        Long uid = UserContext.userId();
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> items = notificationService.recent(uid, limit);
        m.put("items", items);
        m.put("unread", notificationService.unreadCount(uid));
        m.put("read", notificationService.readCount(uid));
        return Result.success(m);
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Object>> unreadCount() {
        return Result.success(Map.of("unread", notificationService.unreadCount(UserContext.userId())));
    }

    @PostMapping("/{id}/read")
    public Result<Map<String, Object>> markRead(@PathVariable Long id) {
        int n = notificationService.markRead(UserContext.userId(), id);
        return Result.success(Map.of("updated", n, "unread", notificationService.unreadCount(UserContext.userId())));
    }

    @PostMapping("/read-all")
    public Result<Map<String, Object>> markAllRead() {
        Long uid = UserContext.userId();
        int n = notificationService.markAllRead(uid);
        return Result.success(Map.of("updated", n, "unread", notificationService.unreadCount(uid)));
    }

    /**
     * 删除单条：仅限自己的、且已读的通知。
     * 未读通知是还没处理的线索，不允许删掉当作没收到。
     */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id) {
        Long uid = UserContext.userId();
        int n = notificationService.deleteRead(uid, id);
        if (n == 0) {
            throw new BizException(400, "未读通知不能删除，请先点开阅读；或该通知已不存在");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", n);
        m.put("unread", notificationService.unreadCount(uid));
        m.put("read", notificationService.readCount(uid));
        return Result.success("已删除", m);
    }

    /** 一键删除全部已读；未读一条都不动 */
    @DeleteMapping("/read")
    public Result<Map<String, Object>> deleteAllRead() {
        Long uid = UserContext.userId();
        int n = notificationService.deleteAllRead(uid);
        if (n == 0) {
            throw new BizException(400, "当前没有可删除的已读通知");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", n);
        m.put("unread", notificationService.unreadCount(uid));
        m.put("read", notificationService.readCount(uid));
        return Result.success("已删除 " + n + " 条已读通知", m);
    }
}
