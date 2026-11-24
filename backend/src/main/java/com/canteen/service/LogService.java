package com.canteen.service;

import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.entity.OperationLog;
import com.canteen.mapper.OperationLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

/**
 * 操作日志（审计）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogService {

    private final OperationLogMapper logMapper;

    public void record(String module, String content) {
        record(module, content, "成功");
    }

    public void record(String module, String content, String result) {
        record((LoginUser) null, module, content, result);
    }

    public void record(LoginUser actor, String module, String content) {
        record(actor, module, content, "成功");
    }

    /**
     * 显式指定操作人。登录 / 登录失败这类场景发生在 token 签发之前，
     * UserContext 当时为空，若走隐式取上下文会全部落成「未知」。
     */
    public void record(LoginUser actor, String module, String content, String result) {
        try {
            OperationLog l = new OperationLog();
            LoginUser lu = actor != null ? actor : UserContext.getOrNull();
            if (lu != null) {
                l.setUserId(lu.getUserId());
                l.setOperator(displayName(lu));
                l.setRoleName(roleName(lu.getRole()));
            } else {
                l.setOperator("未知");
                l.setRoleName("—");
            }
            l.setModule(module);
            l.setContent(content.length() > 280 ? content.substring(0, 280) : content);
            l.setIp(clientIp());
            l.setResult(result);
            l.setCreateTime(LocalDateTime.now());
            logMapper.insert(l);
        } catch (Exception e) {
            log.warn("写入操作日志失败: {}", e.getMessage());
        }
    }

    /** 操作人展示名：真实姓名 → 账号名 → 未知，任一层有值就不要显示「未知」 */
    private static String displayName(LoginUser lu) {
        if (lu.getRealName() != null && !lu.getRealName().isBlank()) {
            return lu.getRealName().trim();
        }
        if (lu.getUsername() != null && !lu.getUsername().isBlank()) {
            return lu.getUsername().trim();
        }
        return "未知";
    }

    public static String roleName(String role) {
        return switch (role == null ? "" : role) {
            case "ADMIN" -> "系统管理员";
            case "FARMER" -> "农户/合作社";
            case "PURCHASER" -> "食堂采购员";
            case "INSPECTOR" -> "质检配送员";
            default -> "—";
        };
    }

    public static String clientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return "127.0.0.1";
            }
            HttpServletRequest req = attrs.getRequest();
            String ip = req.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank()) {
                return ip.split(",")[0].trim();
            }
            ip = req.getRemoteAddr();
            return "0:0:0:0:0:0:0:1".equals(ip) ? "127.0.0.1" : ip;
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
