package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.canteen.common.BizException;
import com.canteen.common.JwtUtil;
import com.canteen.common.LoginUser;
import com.canteen.common.PasswordPolicy;
import com.canteen.common.TokenRevocationStore;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录认证与用户信息。
 *
 * <p>修复 P1-1：注销时吊销 jti；修复 P1-2：演示账号信息默认不再对外暴露；
 * 修复 P1-4：登录失败计数改为 SQL 原子自增，所有失败路径统一文案与耗时。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final int MAX_FAIL = 5;
    private static final int LOCK_MINUTES = 10;
    private static final String FAIL_MESSAGE = "用户名或密码错误";
    /** 账号不存在时用于保持恒定耗时的伪哈希 */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5V1uW9YQ2pK0n8r3sT6xLmB4yC7dE1aG";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final JwtUtil jwtUtil;
    private final LogService logService;
    private final TokenRevocationStore revocationStore;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${canteen.security.expose-demo-accounts:false}")
    private boolean exposeDemoAccounts;

    public Map<String, Object> login(LoginDTO dto) {
        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>().eq("username", dto.getUsername()));
        if (user == null) {
            // 与真实校验保持相近耗时，避免通过响应时间枚举账号
            encoder.matches(dto.getPassword(), DUMMY_HASH);
            throw new BizException(401, FAIL_MESSAGE);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            encoder.matches(dto.getPassword(), DUMMY_HASH);
            log.info("停用账号尝试登录: {}", user.getUsername());
            throw new BizException(401, FAIL_MESSAGE);
        }
        if (user.getLockUntil() != null && user.getLockUntil().isAfter(LocalDateTime.now())) {
            encoder.matches(dto.getPassword(), DUMMY_HASH);
            throw new BizException(401, FAIL_MESSAGE);
        }
        if (!matches(dto.getPassword(), user.getPassword())) {
            int fail = atomicIncreaseFail(user.getId());
            if (fail >= MAX_FAIL) {
                SysUser lock = new SysUser();
                lock.setId(user.getId());
                lock.setLoginFail(0);
                lock.setLockUntil(LocalDateTime.now().plusMinutes(LOCK_MINUTES));
                userMapper.updateById(lock);
                log.info("账号 {} 连续输错 {} 次，锁定 {} 分钟", user.getUsername(), fail, LOCK_MINUTES);
            }
            logService.record(actorOf(user), "登录", "账号 " + user.getUsername() + " 密码错误（累计 " + fail + " 次）", "失败");
            throw new BizException(401, FAIL_MESSAGE);
        }
        // 登录页角色选择必须真实生效：密码校验通过后才比对角色，避免用角色提示枚举账号
        if (dto.getExpectedRole() != null && !dto.getExpectedRole().isBlank()
                && !dto.getExpectedRole().equals(user.getRoleType())) {
            logService.record(actorOf(user), "登录", "账号 " + user.getUsername() + " 角色选择不符（期望 "
                    + dto.getExpectedRole() + "，实际 " + user.getRoleType() + "）", "失败");
            throw new BizException(401, "该账号不是「" + LogService.roleName(dto.getExpectedRole())
                    + "」，请切换到正确角色，或取消角色选择后登录");
        }
        SysUser upd = new SysUser();
        upd.setId(user.getId());
        upd.setLoginFail(0);
        upd.setLockUntil(null);
        upd.setOnline(1);
        upd.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(upd);

        LoginUser lu = buildLoginUser(user);
        String token = jwtUtil.create(claimsOf(user), Boolean.TRUE.equals(dto.getRemember()));
        logService.record(lu, "登录", "用户 " + user.getUsername() + " 登录成功");

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("token", token);
        res.put("user", userInfo(user, lu));
        res.put("roleName", LogService.roleName(user.getRoleType()));
        res.put("menus", menus(user.getId()));
        res.put("perms", userMapper.selectPerms(user.getId()));
        res.put("needChangePwd", needChangePwd(user));
        return res;
    }

    /** 新建账号或被管理员重置后为 true：未改密前只放行 /api/auth/** */
    private boolean needChangePwd(SysUser user) {
        return user.getNeedChangePwd() != null && user.getNeedChangePwd() == 1;
    }

    /** SQL 层原子自增，避免并发下 read-modify-write 丢失更新导致锁定被绕过。 */
    private int atomicIncreaseFail(Long userId) {
        userMapper.update(null, new UpdateWrapper<SysUser>()
                .setSql("login_fail = IFNULL(login_fail, 0) + 1")
                .eq("id", userId));
        SysUser fresh = userMapper.selectById(userId);
        return fresh == null || fresh.getLoginFail() == null ? MAX_FAIL : fresh.getLoginFail();
    }

    private boolean matches(String raw, String encoded) {
        try {
            return raw != null && encoded != null && encoder.matches(raw, encoded);
        } catch (Exception e) {
            return false;
        }
    }

    /** 写登录日志用的轻量操作人：登录过程中还没有 token，只能从库里查到的账号构造 */
    private LoginUser actorOf(SysUser user) {
        LoginUser lu = new LoginUser();
        lu.setUserId(user.getId());
        lu.setUsername(user.getUsername());
        lu.setRealName(user.getRealName());
        lu.setRole(user.getRoleType());
        return lu;
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
        res.put("needChangePwd", needChangePwd(user));
        return res;
    }

    /**
     * 用户自助修改密码（不经过管理员）。
     *
     * <p>改完立刻吊销此前签发的所有 token，并用 cutoff+1 重新签发本次会话的 token，
     * 这样"别处已登录的会话"被踢下线，当前这个人不用重新登录。</p>
     */
    @Transactional
    public Map<String, Object> changePassword(String currentPassword, String newPassword) {
        LoginUser lu = UserContext.get();
        SysUser user = userMapper.selectById(lu.getUserId());
        if (user == null) {
            throw new BizException(401, "账号不存在，请重新登录");
        }
        if (!matches(currentPassword, user.getPassword())) {
            logService.record("账号安全", "用户 " + user.getUsername() + " 修改密码时当前密码校验失败", "失败");
            throw new BizException(400, "当前密码不正确");
        }
        if (newPassword == null || newPassword.equals(currentPassword)) {
            throw new BizException(400, "新密码不能与当前密码相同");
        }
        PasswordPolicy.validate(newPassword);

        user.setPassword(encoder.encode(newPassword));
        user.setNeedChangePwd(0);
        user.setPwdUpdateTime(LocalDateTime.now());
        user.setLoginFail(0);
        user.setLockUntil(null);
        userMapper.updateById(user);

        long cutoff = System.currentTimeMillis();
        revocationStore.revokeUserBefore(user.getId(), cutoff);
        String token = jwtUtil.create(claimsOf(user), false, cutoff + 1);

        logService.record("账号安全", "用户 " + user.getUsername() + " 修改了登录密码");
        LoginUser fresh = buildLoginUser(user);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("token", token);
        res.put("user", userInfo(user, fresh));
        res.put("roleName", LogService.roleName(user.getRoleType()));
        res.put("needChangePwd", false);
        return res;
    }

    private Map<String, Object> claimsOf(SysUser user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("role", user.getRoleType());
        return claims;
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
        List<SysMenu> all = new ArrayList<>(menuMapper.selectList(new QueryWrapper<SysMenu>()
                .apply("id IN (SELECT menu_id FROM sys_role_menu WHERE role_id = " +
                        "(SELECT role_id FROM sys_user_role WHERE user_id = {0} LIMIT 1))", userId)
                .orderByAsc("sort")));
        // 兜底：角色只授权了子菜单、漏配父菜单时自动补齐父节点。
        // 否则整组菜单会在侧边栏静默消失（曾导致管理员看不到「数据看板」）。
        Set<Long> grantedIds = all.stream().map(SysMenu::getId).collect(Collectors.toSet());
        Set<Long> missingParents = all.stream()
                .map(SysMenu::getParentId)
                .filter(pid -> pid != null && pid != 0L && !grantedIds.contains(pid))
                .collect(Collectors.toSet());
        if (!missingParents.isEmpty()) {
            all.addAll(menuMapper.selectBatchIds(missingParents));
            all.sort(Comparator.comparing(SysMenu::getSort, Comparator.nullsLast(Integer::compareTo)));
            log.info("菜单父节点未授权，已自动补齐：userId={}, parentIds={}", userId, missingParents);
        }
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
        if (lu == null) {
            return;
        }
        long exp = lu.getTokenExpiresAt() == null
                ? System.currentTimeMillis() + 7L * 24 * 3600 * 1000
                : lu.getTokenExpiresAt();
        revocationStore.revokeJti(lu.getJti(), exp);
        SysUser upd = new SysUser();
        upd.setId(lu.getUserId());
        upd.setOnline(0);
        userMapper.updateById(upd);
        logService.record("登录", "用户 " + lu.getUsername() + " 退出登录");
    }

    /** 登录页角色快捷选择。默认不返回演示账号，避免未鉴权接口泄露可用账号名。 */
    public List<Map<String, Object>> roles() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SysRole r : roleMapper.selectList(new QueryWrapper<SysRole>().orderByAsc("id"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", r.getRoleCode());
            m.put("name", r.getRoleName());
            m.put("description", r.getDescription());
            m.put("dataScope", r.getDataScope());
            if (exposeDemoAccounts) {
                m.put("demoAccount", switch (r.getRoleCode()) {
                    case "ADMIN" -> "admin";
                    case "FARMER" -> "farmer24";
                    case "PURCHASER" -> "purchaser01";
                    case "INSPECTOR" -> "inspector01";
                    default -> "";
                });
            }
            list.add(m);
        }
        return list;
    }
}
