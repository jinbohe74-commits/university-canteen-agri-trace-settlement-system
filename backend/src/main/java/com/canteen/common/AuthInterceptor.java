package com.canteen.common;

import com.canteen.entity.SysUser;
import com.canteen.mapper.CanteenMapper;
import com.canteen.mapper.FarmerMapper;
import com.canteen.mapper.SysUserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Date;

/**
 * JWT 认证 + 角色鉴权拦截器。
 *
 * <p>修复 P0-1：服务端按 URL 前缀强制角色校验，前端菜单/localStorage 不再参与权限判定。
 * 修复 P1-1：校验 token 吊销记录（注销 / 改密 / 停用）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final TokenRevocationStore revocationStore;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        if (token == null || token.isBlank()) {
            writeError(response, 401, "未登录或登录已过期");
            return false;
        }
        try {
            Claims claims = jwtUtil.parse(token);
            Long userId = claims.get("userId", Number.class).longValue();
            String jti = claims.getId();
            Date issuedAt = claims.getIssuedAt();
            Number ms = claims.get("iatMs", Number.class);
            long issuedMillis = ms == null
                    ? (issuedAt == null ? 0L : issuedAt.getTime()) : ms.longValue();
            if (revocationStore.isRevoked(userId, jti, issuedMillis)) {
                writeError(response, 401, "登录凭证已失效，请重新登录");
                return false;
            }
            SysUser user = userMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                writeError(response, 403, "账号不存在或已被停用");
                return false;
            }
            // 角色一律以数据库为准，忽略 token 里携带的 role（防止伪造篡改）
            String role = user.getRoleType();

            // 首登/重置后必须先改密：只放行"改密、看自己、退出"三类接口，
            // 否则管理员给的口令会被长期沿用，等于没有密码机制。
            if (user.getNeedChangePwd() != null && user.getNeedChangePwd() == 1
                    && !passwordFreePath(path(request))) {
                writeError(response, 428, "当前为初始密码，请先修改密码后再使用系统");
                return false;
            }

            String required = requiredRole(path(request));
            if (required != null && !required.equals(role)) {
                log.warn("越权拦截: user={} role={} {} {}", user.getUsername(), role,
                        request.getMethod(), request.getRequestURI());
                writeError(response, 403, "无权访问该资源");
                return false;
            }
            LoginUser lu = new LoginUser();
            lu.setUserId(userId);
            lu.setUsername(user.getUsername());
            lu.setRealName(user.getRealName());
            lu.setRole(role);
            lu.setJti(jti);
            lu.setTokenExpiresAt(claims.getExpiration() == null ? null : claims.getExpiration().getTime());
            if ("FARMER".equals(role)) {
                lu.setFarmerId(farmerMapper.selectFarmerIdByUserId(userId));
            } else if ("PURCHASER".equals(role)) {
                lu.setCanteenId(canteenMapper.selectCanteenIdByUserId(userId));
            }
            UserContext.set(lu);
            return true;
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            writeError(response, 401, "登录已过期，请重新登录");
            return false;
        } catch (Exception e) {
            log.warn("token 解析失败: {}", e.getMessage());
            writeError(response, 401, "登录凭证无效，请重新登录");
            return false;
        }
    }

    /** 未改密时仍允许访问的接口：改密本身、读取自己、退出登录。 */
    static boolean passwordFreePath(String path) {
        return path.equals("/api/auth/password")
                || path.equals("/api/auth/me")
                || path.equals("/api/auth/logout");
    }

    /** URL 前缀 → 允许的角色；返回 null 表示任意已登录用户均可访问。 */
    static String requiredRole(String path) {
        if (isUnder(path, "/api/admin")) {
            return "ADMIN";
        }
        if (isUnder(path, "/api/farmer")) {
            return "FARMER";
        }
        if (isUnder(path, "/api/purchaser")) {
            return "PURCHASER";
        }
        if (isUnder(path, "/api/inspector")) {
            return "INSPECTOR";
        }
        return null;
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeError(HttpServletResponse response, int code, String message) throws Exception {
        // 修复 P3：返回真实 HTTP 状态，业务码仍保留在 body 中兼容前端 axios 封装
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
