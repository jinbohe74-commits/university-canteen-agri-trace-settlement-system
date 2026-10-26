package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.canteen.common.BizException;
import com.canteen.common.JwtUtil;
import com.canteen.common.LoginUser;
import com.canteen.common.UserContext;
import com.canteen.dto.LoginDTO;
import com.canteen.entity.Canteen;
import com.canteen.entity.Farmer;
import com.canteen.entity.SysMenu;
import com.canteen.entity.SysRole;
import com.canteen.entity.SysUser;
import com.canteen.mapper.CanteenMapper;
import com.canteen.mapper.FarmerMapper;
import com.canteen.mapper.SysMenuMapper;
import com.canteen.mapper.SysRoleMapper;
import com.canteen.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 登录认证与用户信息
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAIL = 5;
    private static final int LOCK_MINUTES = 10;

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final JwtUtil jwtUtil;
    private final LogService logService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public Map<String, Object> login(LoginDTO dto) {
        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", dto.getUsername()));
        if (user == null) {
            throw new BizException(401, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(403, "账号已被停用，请联系系统管理员");
        }
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {
            long minutes = java.time.Duration.between(LocalDateTime.now(), user.getLockUntil()).toMinutes() + 1;
            throw new BizException(423, "连续输错 5 次，账号已锁定，请 " + minutes + " 分钟后重试");
        }
        if (!matches(dto.getPassword(), user.getPassword())) {
            int fail = (user.getLoginFail() == null ? 0 : user.getLoginFail()) + 1;
            SysUser upd = new SysUser();
            upd.setId(user.getId());
            upd.setLoginFail(fail);
            if (fail >= MAX_FAIL) {
                upd.setLockUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                upd.setLoginFail(0);
            }
            userMapper.updateById(upd);
            logService.record("登录", "账号 " + user.getUsername() + " 密码错误（第 " + fail + " 次）", "失败");
            if (fail >= MAX_FAIL) {
                throw new BizException(423, "连续输错 5 次，账号已锁定 10 分钟");
            }
            throw new BizException(401, "用户名或密码错误，还可尝试 " + (MAX_FAIL - fail) + " 次");
        }
        SysUser upd = new SysUser();
        upd.setId(user.getId());
        upd.setLoginFail(0);
        upd.setLockUntil(null);
        upd.setOnline(1);
        upd.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(upd);

        LoginUser lu = buildLoginUser(user);
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("role", user.getRoleType());
        String token = jwtUtil.create(claims, Boolean.TRUE.equals(dto.getRemember()));
        logService.record("登录", "用户 " + user.getUsername() + " 登录成功");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("token", token);
        res.put("user", userInfo(user, lu));
        res.put("roleName", LogService.roleName(user.getRoleType()));
        res.put("menus", menus(user.getId()));
        res.put("perms", userMapper.selectPerms(user.getId()));
        return res;
    }

    private boolean matches(String raw, String encoded) {
        try {
            return encoder.matches(raw, encoded);
        } catch (Exception e) {
            return false;
        }
    }

    private LoginUser buildLoginUser(SysUser user) {
        LoginUser lu = new LoginUser();
        lu.setUserId(user.getId());
        lu.setUsername(user.getUsername());
        lu.setRealName(user.getRealName());
        lu.setRole(user.getRoleType());
        if ("FARMER".equals(user.getRoleType())) {
            lu.setFarmerId(farmerMapper.selectFarmerIdByUserId(user.getId()));
        } else if ("PURCHASER".equals(user.getRoleType())) {
            lu.setCanteenId(canteenMapper.selectCanteenIdByUserId(user.getId()));
        }
        return lu;
    }

    public Map<String, Object> me() {
        LoginUser lu = UserContext.get();
        SysUser user = userMapper.selectById(lu.getUserId());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("user", userInfo(user, lu));
        res.put("roleName", LogService.roleName(user.getRoleType()));
        res.put("menus", menus(user.getId()));
        res.put("perms", userMapper.selectPerms(user.getId()));
        return res;
    }

    private Map<String, Object> userInfo(SysUser user, LoginUser lu) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", user.getId());
        info.put("username", user.getUsername());
        info.put("realName", user.getRealName());
        info.put("phone", user.getPhone());
        info.put("role", user.getRoleType());
        info.put("avatar", user.getAvatar() == null || user.getAvatar().isBlank()
                ? (user.getRealName() == null ? "用" : user.getRealName().substring(0, 1))
                : user.getAvatar());
        info.put("farmerId", lu.getFarmerId());
        info.put("canteenId", lu.getCanteenId());
        if (lu.getFarmerId() != null) {
            Farmer f = farmerMapper.selectById(lu.getFarmerId());
            if (f != null) {
                info.put("orgName", f.getName());
                info.put("orgType", f.getType());
                info.put("creditScore", f.getCreditScore());
                info.put("certStatus", f.getStatus());
                info.put("canteenName", null);
            }
        } else if (lu.getCanteenId() != null) {
            Canteen c = canteenMapper.selectById(lu.getCanteenId());
            if (c != null) {
                info.put("orgName", c.getName());
                info.put("orgType", c.getCampus());
                info.put("campus", c.getCampus());
                info.put("canteenName", c.getName());
            }
        } else {
            info.put("orgName", "农校直供平台");
            info.put("orgType", "平台");
        }
        return info;
    }

    /** 角色菜单树 */
    public List<Map<String, Object>> menus(Long userId) {
        List<SysMenu> all = menuMapper.selectList(new QueryWrapper<SysMenu>()
                .inSql("id", "SELECT menu_id FROM sys_role_menu WHERE role_id = " +
                        "(SELECT role_id FROM sys_user_role WHERE user_id = " + userId + " LIMIT 1)")
                .orderByAsc("sort"));
        List<Map<String, Object>> tree = new ArrayList<>();
        for (SysMenu m : all) {
            if (m.getParentId() != null && m.getParentId() == 0) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", m.getId());
                node.put("name", m.getMenuName());
                node.put("icon", m.getIcon());
                List<Map<String, Object>> children = new ArrayList<>();
                for (SysMenu c : all) {
                    if (m.getId().equals(c.getParentId())) {
                        Map<String, Object> ch = new LinkedHashMap<>();
                        ch.put("id", c.getId());
                        ch.put("name", c.getMenuName());
                        ch.put("path", c.getPath());
                        ch.put("icon", c.getIcon());
                        ch.put("perms", c.getPerms());
                        children.add(ch);
                    }
                }
                node.put("children", children);
                tree.add(node);
            }
        }
        return tree;
    }

    public void logout() {
        LoginUser lu = UserContext.getOrNull();
        if (lu != null) {
            SysUser upd = new SysUser();
            upd.setId(lu.getUserId());
            upd.setOnline(0);
            userMapper.updateById(upd);
            logService.record("登录", "用户 " + lu.getUsername() + " 退出登录");
        }
    }

    /** 登录页角色快捷选择 */
    public List<Map<String, Object>> roles() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysRole r : roleMapper.selectList(new QueryWrapper<SysRole>().orderByAsc("id"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.getRoleCode());
            m.put("name", r.getRoleName());
            m.put("description", r.getDescription());
            m.put("dataScope", r.getDataScope());
            m.put("demoAccount", switch (r.getRoleCode()) {
                case "ADMIN" -> "admin";
                case "FARMER" -> "farmer24";
                case "PURCHASER" -> "purchaser01";
                case "INSPECTOR" -> "inspector01";
                default -> "";
            });
            list.add(m);
        }
        return list;
    }
}
