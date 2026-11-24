package com.canteen.config;

import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.service.RealtimeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一捕获所有写操作，产出「静默的缓存失效信号」，让其它角色的在线页面自动刷新。
 *
 * <p>与业务通知严格分离（2026-11-11）：
 * - 用户能看到的「动态」= sys_notification，由 NotificationService 按订单归属定向发布；
 * - 这里发的 CACHE_INVALIDATE 只是技术信号，前端不进通知中心、不产生未读数、不展示正文，
 *   仅触发 canteen:data-changed 让页面重新拉数据。它对所有在线连接可见，
 *   因为「别人改了货源/订单，我的列表要刷新」本来就与角色无关。
 *
 * <p>为什么仍用 AOP：28 个业务接口逐个挂刷新信号必然漏；写操作都在 controller 层，
 * 一处切面即可覆盖全部动作。发送注册在 afterCommit，避免事务未提交就刷新读到旧数据。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RealtimeAspect {

    private final RealtimeService realtime;

    /** 路径关键字 -> [功能模块, 动作描述] */
    private static final Map<String, String[]> RULES = new LinkedHashMap<>();
    static {
        RULES.put("/users/", new String[]{"用户与权限", "变更了账号状态或权限"});
        RULES.put("/users", new String[]{"用户与权限", "更新了用户账号"});
        RULES.put("/sources", new String[]{"货源管理", "更新了供货信息"});
        RULES.put("/demands", new String[]{"采购需求", "更新了采购需求"});
        RULES.put("/orders/", new String[]{"订单管理", "推进了订单状态"});
        RULES.put("/orders", new String[]{"订单管理", "更新了订单"});
        RULES.put("/match", new String[]{"智能匹配", "执行了供需匹配"});
        RULES.put("/inbound", new String[]{"入库确认", "确认了收货入库"});
        RULES.put("/quality", new String[]{"质检管理", "提交了质检报告"});
        RULES.put("/deliveries", new String[]{"冷链配送", "更新了配送任务"});
        RULES.put("/exceptions", new String[]{"异常上报", "上报了配送异常"});
        RULES.put("/settlements", new String[]{"结算管理", "更新了结算单"});
        RULES.put("/evaluations", new String[]{"采购评价", "提交了履约评价"});
        RULES.put("/warnings", new String[]{"食安预警", "处置了预警"});
        RULES.put("/farmers", new String[]{"农户档案", "更新了农户档案"});
        RULES.put("/canteens", new String[]{"食堂档案", "更新了食堂档案"});
        RULES.put("/products", new String[]{"农产品档案", "更新了农产品档案"});
        RULES.put("/config", new String[]{"系统参数", "调整了系统参数"});
        RULES.put("/trace", new String[]{"批次溯源", "写入了溯源存证"});
        RULES.put("/files", new String[]{"附件管理", "上传了业务附件"});
    }

    @Pointcut("execution(* com.canteen.controller..*.*(..))")
    public void controllerMethods() {}

    @AfterReturning(pointcut = "controllerMethods()")
    public void afterWrite() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return;
            HttpServletRequest req = attrs.getRequest();
            String method = req.getMethod();
            if (!("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) return;
            String path = req.getRequestURI();
            if (path.startsWith("/api/auth/") || path.startsWith("/api/common/events")
                    || path.startsWith("/api/common/health") || path.equals("/error")) return;
            // 业务失败由异常抛出，@AfterReturning 只覆盖成功路径；这里再排除显式错误响应
            if (req.getAttribute("realtime.skip") != null) return;

            LoginUser lu = UserContext.getOrNull();
            if (lu == null) return;

            String[] rule = describe(path);
            // 不带操作者姓名/动作描述：这不是给人看的动态，只是「该模块数据变了」的信号
            String content = rule[0] + " 数据已更新（" + path.replace("/api/", "") + "）";
            Runnable send = () -> {
                try {
                    realtime.publish("CACHE_INVALIDATE", rule[0], content, "", null, path, lu);
                } catch (Exception e) {
                    log.warn("缓存失效信号发送失败: {}", e.getMessage());
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { send.run(); }
                });
            } else {
                send.run();
            }
        } catch (Exception e) {
            log.warn("实时事件切面异常（不影响主业务）: {}", e.getMessage());
        }
    }

    private static String[] describe(String path) {
        for (Map.Entry<String, String[]> e : RULES.entrySet()) {
            if (path.contains(e.getKey())) return e.getValue();
        }
        return new String[]{"业务操作", "提交了数据变更"};
    }
}
