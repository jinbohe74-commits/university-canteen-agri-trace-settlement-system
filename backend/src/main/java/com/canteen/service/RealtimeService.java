package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.canteen.common.BizException;
import com.canteen.common.LoginUser;
import com.canteen.entity.SysEvent;
import com.canteen.mapper.SysEventMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全角色实时反馈：写操作统一落 sys_event，SSE 主动推送，轮询接口做断线兜底。
 *
 * <p>设计要点：事件表是唯一事实源，SSE 只负责「立刻通知」，轮询负责「补漏」；
 * 因此即使 SSE 被代理缓冲或浏览器断线，也不会丢事件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeService {

    private final SysEventMapper eventMapper;
    private final JdbcTemplate jdbc;

    /** subscriberId -> 订阅者 */
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    /** 一次性 SSE 票据：避免把 JWT 放进 EventSource 的 URL */
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    private static final long TICKET_TTL_MS = 60_000L;
    private static final long EMITTER_TTL_MS = 30 * 60 * 1000L;

    @PostConstruct
    public void ensureTable() {
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys_event (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  event_type VARCHAR(40) NOT NULL,
                  module VARCHAR(50) NOT NULL,
                  content VARCHAR(300) NOT NULL,
                  operator_id BIGINT DEFAULT NULL,
                  operator_name VARCHAR(60) DEFAULT NULL,
                  operator_role VARCHAR(30) DEFAULT NULL,
                  target_roles VARCHAR(120) DEFAULT NULL,
                  target_user_id BIGINT DEFAULT NULL,
                  ref_path VARCHAR(160) DEFAULT NULL,
                  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (id),
                  KEY idx_create_time (create_time)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨角色实时事件表'
                """);
        } catch (Exception e) {
            log.error("sys_event 自动建表失败，实时反馈将不可用", e);
        }
    }

    /* ------------------------------------------------ 票据与订阅 */

    public String issueTicket(LoginUser lu) {
        String id = UUID.randomUUID().toString().replace("-", "");
        tickets.put(id, new Ticket(lu.getUserId(), lu.getRole(), System.currentTimeMillis() + TICKET_TTL_MS));
        return id;
    }

    public SseEmitter subscribe(String ticket) {
        Ticket t = ticket == null ? null : tickets.remove(ticket);
        if (t == null || t.expireAt < System.currentTimeMillis()) {
            throw new BizException(401, "实时连接票据无效，请重新登录");
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        SseEmitter emitter = new SseEmitter(EMITTER_TTL_MS);
        Subscriber sub = new Subscriber(id, t.userId, t.role, emitter);
        subscribers.put(id, sub);
        emitter.onCompletion(() -> subscribers.remove(id));
        emitter.onTimeout(() -> subscribers.remove(id));
        emitter.onError(e -> subscribers.remove(id));
        try {
            emitter.send(SseEmitter.event().name("ready")
                    .data(Map.of("cursor", maxId(), "userId", t.userId), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            subscribers.remove(id);
        }
        return emitter;
    }

    /* ------------------------------------------------ 发布与查询 */

    public SysEvent publish(String type, String module, String content,
                            String targetRoles, Long targetUserId, String refPath,
                            LoginUser operator) {
        SysEvent e = new SysEvent();
        e.setEventType(type);
        e.setModule(module);
        e.setContent(content.length() > 280 ? content.substring(0, 280) : content);
        e.setTargetRoles(targetRoles == null ? "" : targetRoles);
        e.setTargetUserId(targetUserId);
        e.setRefPath(refPath);
        e.setCreateTime(LocalDateTime.now());
        if (operator != null) {
            e.setOperatorId(operator.getUserId());
            e.setOperatorName(operator.getRealName());
            e.setOperatorRole(operator.getRole());
        } else {
            e.setOperatorName("系统");
        }
        try {
            eventMapper.insert(e);
            broadcast(e);
        } catch (Exception ex) {
            // 事件落库失败不能影响主业务（L-006：辅助链路不得拖垮主链路）
            log.warn("实时事件发布失败: {}", ex.getMessage());
        }
        return e;
    }

    public long maxId() {
        try {
            return eventMapper.maxId();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 轮询兜底：只返回对该用户可见的新事件 */
    public List<Map<String, Object>> since(long lastId, Long userId, String role, int limit) {
        int n = Math.min(Math.max(limit, 1), 100);
        List<SysEvent> all;
        try {
            all = eventMapper.since(Math.max(lastId, 0), n);
        } catch (Exception e) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysEvent e : all) {
            if (visible(e, userId, role)) out.add(toMap(e));
        }
        return out;
    }

    /** 通知中心首屏：最近 N 条对自己可见的事件（倒序） */
    public List<Map<String, Object>> recent(Long userId, String role, int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        List<SysEvent> all;
        try {
            all = eventMapper.selectList(new LambdaQueryWrapper<SysEvent>()
                    .orderByDesc(SysEvent::getId).last("LIMIT " + (n * 4)));
        } catch (Exception e) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysEvent e : all) {
            if (visible(e, userId, role)) {
                out.add(toMap(e));
                if (out.size() >= n) break;
            }
        }
        return out;
    }

    /* ------------------------------------------------ 内部 */

    private void broadcast(SysEvent e) {
        Map<String, Object> payload = toMap(e);
        for (Subscriber s : subscribers.values()) {
            if (!visible(e, s.userId, s.role)) continue;
            try {
                s.emitter.send(SseEmitter.event().name("event").data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                subscribers.remove(s.id);
            }
        }
    }

    private boolean visible(SysEvent e, Long userId, String role) {
        if (e.getTargetUserId() != null) {
            return e.getTargetUserId().equals(userId);
        }
        if (!StringUtils.hasText(e.getTargetRoles())) return true;
        return Arrays.asList(e.getTargetRoles().split(",")).contains(role);
    }

    private Map<String, Object> toMap(SysEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("eventType", e.getEventType());
        m.put("module", e.getModule());
        m.put("content", e.getContent());
        m.put("operatorId", e.getOperatorId());
        m.put("operatorName", e.getOperatorName());
        m.put("operatorRole", e.getOperatorRole());
        m.put("targetUserId", e.getTargetUserId());
        m.put("refPath", e.getRefPath());
        m.put("createTime", e.getCreateTime() == null ? null : e.getCreateTime().toString());
        return m;
    }

    /** 心跳注释行，防止长连接被代理判空闲断开 */
    @Scheduled(fixedDelay = 20_000L)
    public void heartbeat() {
        for (Subscriber s : subscribers.values()) {
            try {
                s.emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception ex) {
                subscribers.remove(s.id);
            }
        }
        tickets.entrySet().removeIf(x -> x.getValue().expireAt < System.currentTimeMillis());
    }

    private record Ticket(Long userId, String role, long expireAt) {}
    private record Subscriber(String id, Long userId, String role, SseEmitter emitter) {}
}
