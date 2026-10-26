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

/**
 * JWT 认证拦截器：解析 Token -> 组装登录用户 -> 注入数据权限范围
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final SysUserMapper userMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;

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
            SysUser user = userMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1) {
                writeError(response, 403, "账号不存在或已被停用");
                return false;
            }
            LoginUser lu = new LoginUser();
            lu.setUserId(userId);
            lu.setUsername(user.getUsername());
            lu.setRealName(user.getRealName());
            lu.setRole(user.getRoleType());
            if ("FARMER".equals(user.getRoleType())) {
                lu.setFarmerId(farmerMapper.selectFarmerIdByUserId(userId));
            } else if ("PURCHASER".equals(user.getRoleType())) {
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

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeError(HttpServletResponse response, int code, String message) throws Exception {
        response.setStatus(200);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}");
    }
}
