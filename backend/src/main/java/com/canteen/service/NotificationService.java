package com.canteen.service;

import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.entity.Orders;
import com.canteen.entity.SysNotification;
import com.canteen.entity.SysUser;
import com.canteen.mapper.OrdersMapper;
import com.canteen.mapper.SysNotificationMapper;
import com.canteen.mapper.SysUserMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定向业务通知。
 * 设计要点：
 * 1. 一条通知只属于一个 recipient_id，查询永远带 recipient_id 过滤；
 * 2. 接收人从订单归属（farmer.user_id / canteen.user_id）解析，不做角色群发；
 * 3. 通知是旁路，任何异常都吞掉，绝不让主业务失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final SysNotificationMapper notificationMapper;
    private final OrdersMapper ordersMapper;
    private final SysUserMapper userMapper;

    @PostConstruct
    public void init() {
        try {
            notificationMapper.createTableIfAbsent();
        } catch (Exception e) {
            log.warn("通知表初始化失败（若数据库账号无 DDL 权限，请先执行 db/10_notification.sql）：{}", e.getMessage());
        }
    }

    /**
     * 给一张订单的相关方发通知。
     *
     * @param partyRoutes 接收方 → 该方点击后要跳的路由，例如
     *                    FARMER→/farmer/orders、CANTEEN→/purchaser/inbound、
     *                    INSPECTOR→/inspector/tasks、ADMIN→/admin/settlements
     */
    public void toOrderParties(Long orderId, String bizType, String bizNo,
                               String title, String content, Map<String, String> partyRoutes) {
        try {
            Orders o = orderId == null ? null : ordersMapper.selectById(orderId);
            // 同一个账号可能同时被「食堂绑定账号」和「采购员角色」解析出来，
            // 一次业务事件只给他落一条，先命中的路由生效。
            Set<Long> sent = new LinkedHashSet<>();
            for (Map.Entry<String, String> e : partyRoutes.entrySet()) {
                List<Long> ids = resolve(o, e.getKey());
                if (ids.isEmpty()) continue;
                publish(ids, bizType, bizNo, orderId, title, content, e.getValue(), e.getKey(), sent);
            }
        } catch (Exception ex) {
            log.warn("定向通知发布失败（不影响业务）：{}", ex.getMessage());
        }
    }

    /** 给指定角色发通知（用于「新需求可报价」这类无订单归属的场景） */
    public void toRole(String role, String bizType, String bizNo,
                       String title, String content, String route) {
        try {
            List<Long> ids = notificationMapper.userIdsOfRole(role);
            publish(ids, bizType, bizNo, null, title, content, route, role, new LinkedHashSet<>());
        } catch (Exception ex) {
            log.warn("角色通知发布失败（不影响业务）：{}", ex.getMessage());
        }
    }

    private List<Long> resolve(Orders o, String party) {
        return switch (party) {
            case "FARMER" -> o == null ? List.of() : notificationMapper.userIdsOfFarmer(o.getFarmerId());
            // 收货食堂的绑定账号就是这批货的采购责任人，别再群发给所有采购员
            case "CANTEEN", "PURCHASER" -> {
                List<Long> ids = o == null ? List.of() : notificationMapper.userIdsOfCanteen(o.getCanteenId());
                yield ids.isEmpty() ? notificationMapper.userIdsOfRole("PURCHASER") : ids;
            }
            // 谁检的这批就通知谁，而不是整个质检组
            case "INSPECTOR" -> {
                List<Long> ids = o == null ? List.of() : notificationMapper.userIdsOfOrderInspector(o.getId());
                yield ids.isEmpty() ? notificationMapper.userIdsOfRole("INSPECTOR") : ids;
            }
            case "ADMIN" -> notificationMapper.userIdsOfRole(party);
            default -> List.of();
        };
    }

    private void publish(List<Long> recipients, String bizType, String bizNo, Long orderId,
                         String title, String content, String route, String party, Set<Long> sent) {
        LoginUser me = UserContext.getOrNull();
        Long self = me == null ? null : me.getUserId();
        Set<Long> targets = new LinkedHashSet<>();
        for (Long id : recipients) {
            // 不给自己发自己刚做的操作；一次事件里同一个账号只收一条
            if (id != null && !id.equals(self) && sent.add(id)) targets.add(id);
        }
        for (Long uid : targets) {
            SysUser u = userMapper.selectById(uid);
            SysNotification n = new SysNotification();
            n.setBizType(bizType);
            n.setBizNo(bizNo);
            n.setOrderId(orderId);
            if (me != null) {
                n.setSenderId(me.getUserId());
                n.setSenderName(me.getRealName());
                n.setSenderRole(me.getRole());
            }
            n.setRecipientId(uid);
            n.setRecipientName(u == null ? null : u.getRealName());
            n.setRecipientRole(party);
            n.setTitle(title);
            n.setContent(content);
            n.setRoute(route);
            n.setIsRead(0);
            notificationMapper.insert(n);
        }
    }

    /** 当前用户的通知列表（带发送人与业务单号，供顶栏直接展示） */
    public List<Map<String, Object>> recent(Long userId, int limit) {
        List<SysNotification> list = notificationMapper.recent(userId, Math.max(1, Math.min(limit, 200)));
        return list.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.getId());
            m.put("bizType", n.getBizType());
            m.put("bizNo", n.getBizNo());
            m.put("orderId", n.getOrderId());
            m.put("senderName", n.getSenderName());
            m.put("senderRole", n.getSenderRole());
            m.put("recipientName", n.getRecipientName());
            m.put("title", n.getTitle());
            m.put("content", n.getContent());
            m.put("route", n.getRoute());
            m.put("isRead", n.getIsRead());
            m.put("createTime", n.getCreateTime());
            return m;
        }).toList();
    }

    public long unreadCount(Long userId) {
        Long c = notificationMapper.unreadCount(userId);
        return c == null ? 0 : c;
    }

    public int markRead(Long userId, Long id) {
        return notificationMapper.markRead(id, userId);
    }

    public int markAllRead(Long userId) {
        return notificationMapper.markAllRead(userId);
    }

    public int deleteRead(Long userId, Long id) {
        return notificationMapper.deleteRead(id, userId);
    }

    public int deleteAllRead(Long userId) {
        return notificationMapper.deleteAllRead(userId);
    }

    /** 已读条数 = 总数 - 未读，供面板「未读 / 已读」两个分组显示 */
    public long readCount(Long userId) {
        Long total = notificationMapper.total(userId);
        long t = total == null ? 0 : total;
        return Math.max(0, t - unreadCount(userId));
    }
}
