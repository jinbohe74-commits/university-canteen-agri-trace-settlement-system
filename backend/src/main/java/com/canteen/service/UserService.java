package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.LoginUser;
import com.canteen.common.PageResult;
import com.canteen.common.PasswordPolicy;
import com.canteen.common.TokenRevocationStore;
import com.canteen.common.UserContext;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 用户、角色与权限（RBAC）
 *
 * <p>2026-11-11 账号机制收敛：账号不是孤立的一条 sys_user，
 * 而是「账号 + 角色 + 业务档案（农户/食堂）」三件套，三者必须在同一次保存里落全。
 * 历史实现只写前两者，于是会建出"能登录但看不到任何数据"的账号，
 * 以及"没有角色记录、菜单为空"的账号。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final FarmerMapper farmerMapper;
    private final CanteenMapper canteenMapper;
    private final LogService logService;
    private final TokenRevocationStore revocationStore;
    private final RealtimeService realtimeService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 账号规则：字母开头，3-24 位字母/数字/下划线。禁止空格与中文，避免"看着一样、登不进去" */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,23}$");

    private static final Map<String, String> ROLE_NAMES = Map.of(
            "ADMIN", "系统管理员",
            "FARMER", "农户/合作社",
            "PURCHASER", "食堂采购员",
            "INSPECTOR", "质检配送员");

    // ------------------------------------------------------------------ 校验

    private void validatePassword(String password) {
        PasswordPolicy.validate(password);
    }

    private String randomPassword() {
        return PasswordPolicy.random();
    }

    /** 生成符合强度要求的初始密码，避免管理员自己想一个弱口令 */
    public String generatePassword() {
        return randomPassword();
    }

    private String normalizeUsername(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (!USERNAME.matcher(name).matches()) {
            throw new BizException(400, "账号需以字母开头，3-24 位，只能包含字母、数字、下划线");
        }
        return name;
    }

    private String normalizeRole(String roleType) {
        String role = roleType == null ? "" : roleType.trim().toUpperCase(Locale.ROOT);
        if (!ROLE_NAMES.containsKey(role)) {
            throw new BizException(400, "请选择角色（管理员 / 农户 / 采购员 / 质检员）");
        }
        return role;
    }

    /**
     * 角色关系一律以 roleType 为准反查 sys_role。
     * 前端传的 roleId 只作为一致性校验，绝不直接落库——
     * 前端拿不到角色列表时曾建出「sys_user 有 role_type、sys_user_role 没有记录」的账号，
     * 登录后菜单为空、侧边栏一片空白。
     */
    private SysRole resolveRole(String roleType, Long roleId) {
        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleType).last("LIMIT 1"));
        if (role == null) {
            throw new BizException(500, "系统角色「" + roleType + "」未配置，请先在角色管理中初始化");
        }
        if (roleId != null && !roleId.equals(role.getId())) {
            log.warn("前端传入的 roleId={} 与角色编码 {} 不一致，按角色编码落库", roleId, roleType);
        }
        return role;
    }

    // ------------------------------------------------------------------ 列表

    public PageResult<UserVO> page(int page, int size, String keyword, String role) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            qw.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword)
                    .or().like(SysUser::getPhone, keyword));
        }
        if (StringUtils.hasText(role)) {
            qw.eq(SysUser::getRoleType, role);
        }
        qw.orderByAsc(SysUser::getId);
        IPage<SysUser> p = userMapper.selectPage(new Page<>(page, size), qw);

        Map<Long, Farmer> farmers = new HashMap<>();
        for (Farmer f : farmerMapper.selectList(null)) {
            if (f.getUserId() != null) {
                farmers.put(f.getUserId(), f);
            }
        }
        Map<Long, Canteen> canteens = new HashMap<>();
        for (Canteen c : canteenMapper.selectList(null)) {
            if (c.getUserId() != null) {
                canteens.put(c.getUserId(), c);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        return PageResult.of(p, u -> {
            UserVO v = new UserVO();
            v.setId(u.getId());
            v.setUsername(u.getUsername());
            v.setRealName(u.getRealName());
            v.setPhone(u.getPhone());
            v.setRoleType(u.getRoleType());
            v.setAvatar(u.getAvatar());
            v.setStatus(u.getStatus());
            v.setNeedChangePwd(u.getNeedChangePwd());
            v.setPwdUpdateTime(u.getPwdUpdateTime());
            v.setOnline(u.getOnline());
            v.setLastLoginTime(u.getLastLoginTime());
            v.setCreateTime(u.getCreateTime());
            v.setRoleCode(u.getRoleType());
            v.setRoleName(LogService.roleName(u.getRoleType()));
            v.setLastLoginText(u.getLastLoginTime() == null ? "从未登录" : u.getLastLoginTime().format(FMT));
            v.setLocked(u.getLockUntil() != null && u.getLockUntil().isAfter(now));
            Farmer f = farmers.get(u.getId());
            Canteen c = canteens.get(u.getId());
            if (f != null) {
                v.setFarmerId(f.getId());
                v.setOrgName(f.getName());
                v.setOrgNo(f.getFarmerNo());
            } else if (c != null) {
                v.setCanteenId(c.getId());
                v.setOrgName(c.getName());
                v.setOrgNo(c.getCanteenNo());
            } else {
                // 平台岗位本来就不绑档案，不能标成"未绑定档案"制造假告警
                v.setOrgName("ADMIN".equals(u.getRoleType()) || "INSPECTOR".equals(u.getRoleType())
                        ? "平台" : "未绑定档案");
            }
            return v;
        });
    }

    /**
     * 新建账号时可选的业务档案：只返回「没被其他账号占用」的档案。
     * 编辑时把当前账号已绑定的那条也带上，否则下拉里看不到自己。
     */
    public List<Map<String, Object>> bindable(String roleType, Long userId) {
        String role = normalizeRole(roleType);
        List<Map<String, Object>> out = new ArrayList<>();
        if ("FARMER".equals(role)) {
            for (Farmer f : farmerMapper.selectList(new LambdaQueryWrapper<Farmer>().orderByAsc(Farmer::getId))) {
                if (f.getUserId() != null && !f.getUserId().equals(userId)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", f.getId());
                m.put("no", f.getFarmerNo());
                m.put("name", f.getName());
                m.put("extra", (f.getType() == null ? "" : f.getType()) + " · " + (f.getStatus() == null ? "" : f.getStatus()));
                m.put("bound", f.getUserId() != null);
                out.add(m);
            }
        } else if ("PURCHASER".equals(role)) {
            for (Canteen c : canteenMapper.selectList(new LambdaQueryWrapper<Canteen>().orderByAsc(Canteen::getId))) {
                if (c.getUserId() != null && !c.getUserId().equals(userId)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", c.getId());
                m.put("no", c.getCanteenNo());
                m.put("name", c.getName());
                m.put("extra", (c.getCampus() == null ? "" : c.getCampus()) + " · " + (c.getScale() == null ? "" : c.getScale() + " 人"));
                m.put("bound", c.getUserId() != null);
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 角色权限

    public Map<String, Object> roles() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysRole r : roles) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("roleName", r.getRoleName());
            m.put("roleCode", r.getRoleCode());
            m.put("description", r.getDescription());
            m.put("dataScope", r.getDataScope());
            m.put("userCount", userRoleMapper.selectCount(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getRoleId, r.getId())));
            m.put("menuIds", roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                    .eq(SysRoleMenu::getRoleId, r.getId())).stream().map(SysRoleMenu::getMenuId).toList());
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("roles", out);
        res.put("menus", menuTree());
        res.put("dataScopes", List.of(
                Map.of("value", "ALL", "label", "全部数据"),
                Map.of("value", "CANTEEN", "label", "按所属食堂隔离"),
                Map.of("value", "FARMER", "label", "按所属农户隔离"),
                Map.of("value", "SELF", "label", "仅本人负责的数据")));
        return res;
    }

    public List<Map<String, Object>> menuTree() {
        List<SysMenu> all = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>().orderByAsc(SysMenu::getSort));
        List<Map<String, Object>> tree = new ArrayList<>();
        for (SysMenu m : all) {
            if (m.getParentId() != null && m.getParentId() == 0) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", m.getId());
                node.put("name", m.getMenuName());
                node.put("type", m.getMenuType());
                List<Map<String, Object>> children = new ArrayList<>();
                for (SysMenu c : all) {
                    if (m.getId().equals(c.getParentId())) {
                        Map<String, Object> ch = new LinkedHashMap<>();
                        ch.put("id", c.getId());
                        ch.put("name", c.getMenuName());
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

    @Transactional
    public void saveRoleMenus(Long roleId, List<Long> menuIds, String dataScope) {
        SysRole role = roleMapper.selectById(roleId);
        if (role == null) {
            throw new BizException(404, "角色不存在");
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds != null) {
            for (Long mid : menuIds) {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setRoleId(roleId);
                rm.setMenuId(mid);
                roleMenuMapper.insert(rm);
            }
        }
        if (StringUtils.hasText(dataScope)) {
            role.setDataScope(dataScope);
            roleMapper.updateById(role);
        }
        logService.record("权限管理", "保存角色【" + role.getRoleName() + "】权限，菜单 " +
                (menuIds == null ? 0 : menuIds.size()) + " 项，数据范围 " + dataScope);
    }

    // ------------------------------------------------------------------ 账号保存

    /**
     * 新增 / 编辑账号。
     *
     * @param farmerId  农户角色必填：绑定到哪个农户档案
     * @param canteenId 采购员角色必填：绑定到哪个食堂
     * @return user（脱敏）+ initialPassword（仅新建时有值，供管理员一次性转交）
     */
    @Transactional
    public Map<String, Object> save(SysUser u, String password, Long roleId, Long farmerId, Long canteenId) {
        boolean creating = u.getId() == null;
        String roleType = normalizeRole(u.getRoleType());
        u.setRoleType(roleType);
        SysRole role = resolveRole(roleType, roleId);

        String initialPassword = null;
        if (creating) {
            u.setUsername(normalizeUsername(u.getUsername()));
            if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, u.getUsername())) > 0) {
                throw new BizException(409, "账号「" + u.getUsername() + "」已存在");
            }
            if (!StringUtils.hasText(password)) {
                password = randomPassword();
            }
            validatePassword(password);
            initialPassword = password;
            u.setPassword(encoder.encode(password));
            u.setNeedChangePwd(1);
            u.setPwdUpdateTime(null);
            if (u.getStatus() == null) {
                u.setStatus(1);
            }
            u.setOnline(0);
            u.setLoginFail(0);
            userMapper.insert(u);

            SysUserRole ur = new SysUserRole();
            ur.setUserId(u.getId());
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);

            applyBinding(u.getId(), roleType, farmerId, canteenId);
            logService.record("用户管理", "新增「" + role.getRoleName() + "」账号 " + u.getUsername()
                    + "（需首次登录改密）");
        } else {
            SysUser old = userMapper.selectById(u.getId());
            if (old == null) {
                throw new BizException(404, "用户不存在");
            }
            u.setUsername(old.getUsername());
            if (StringUtils.hasText(password)) {
                validatePassword(password);
                u.setPassword(encoder.encode(password));
                u.setNeedChangePwd(1);
                u.setPwdUpdateTime(LocalDateTime.now());
                // 管理员直接改密：吊销该用户此前签发的所有 token
                revocationStore.revokeUser(u.getId());
            }
            if (u.getStatus() == null) {
                u.setStatus(old.getStatus());
            }
            userMapper.updateById(u);

            // 角色关系必须跟着 roleType 走，且保证有且只有一条
            userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, u.getId()));
            SysUserRole ur = new SysUserRole();
            ur.setUserId(u.getId());
            ur.setRoleId(role.getId());
            userRoleMapper.insert(ur);

            applyBinding(u.getId(), roleType, farmerId, canteenId);
            logService.record("用户管理", "编辑「" + role.getRoleName() + "」账号 " + u.getUsername());
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("user", safeUser(u.getId()));
        res.put("initialPassword", initialPassword);
        res.put("needChangePwd", true);
        return res;
    }

    /** 兼容旧调用（无档案绑定参数） */
    @Transactional
    public Map<String, Object> save(SysUser u, String password, Long roleId) {
        return save(u, password, roleId, null, null);
    }

    /**
     * 落库账号与业务档案的一对一绑定。
     *
     * <p>先把该账号此前占用的档案解绑，再按新角色绑定新档案；
     * 目标档案若已被别人占用则直接报错，不做"悄悄抢占"。</p>
     */
    private void applyBinding(Long userId, String roleType, Long farmerId, Long canteenId) {
        // 1) 解除旧绑定（角色变了、或换了绑定对象）
        Farmer curFarmer = farmerMapper.selectOne(new LambdaQueryWrapper<Farmer>()
                .eq(Farmer::getUserId, userId).last("LIMIT 1"));
        Canteen curCanteen = canteenMapper.selectOne(new LambdaQueryWrapper<Canteen>()
                .eq(Canteen::getUserId, userId).last("LIMIT 1"));

        boolean keepFarmer = "FARMER".equals(roleType) && farmerId != null
                && curFarmer != null && farmerId.equals(curFarmer.getId());
        boolean keepCanteen = "PURCHASER".equals(roleType) && canteenId != null
                && curCanteen != null && canteenId.equals(curCanteen.getId());

        if (curFarmer != null && !keepFarmer) {
            farmerMapper.update(null, new LambdaUpdateWrapper<Farmer>()
                    .set(Farmer::getUserId, null).eq(Farmer::getId, curFarmer.getId()));
        }
        if (curCanteen != null && !keepCanteen) {
            canteenMapper.update(null, new LambdaUpdateWrapper<Canteen>()
                    .set(Canteen::getUserId, null).eq(Canteen::getId, curCanteen.getId()));
        }

        // 2) 按角色建立新绑定
        if ("FARMER".equals(roleType)) {
            if (farmerId == null) {
                throw new BizException(400, "农户账号必须绑定一个农户/合作社档案，否则登录后看不到自己的订单");
            }
            Farmer f = farmerMapper.selectById(farmerId);
            if (f == null) {
                throw new BizException(404, "所选农户档案不存在");
            }
            if (f.getUserId() != null && !f.getUserId().equals(userId)) {
                SysUser holder = userMapper.selectById(f.getUserId());
                throw new BizException(409, "农户档案「" + f.getName() + "」已绑定账号 "
                        + (holder == null ? "(已被删除)" : holder.getUsername()) + "，请先解绑或改绑该账号");
            }
            if (!keepFarmer) {
                farmerMapper.update(null, new LambdaUpdateWrapper<Farmer>()
                        .set(Farmer::getUserId, userId).eq(Farmer::getId, farmerId));
            }
        } else if ("PURCHASER".equals(roleType)) {
            if (canteenId == null) {
                throw new BizException(400, "采购员账号必须绑定一个食堂，否则看不到本食堂的采购业务");
            }
            Canteen c = canteenMapper.selectById(canteenId);
            if (c == null) {
                throw new BizException(404, "所选食堂档案不存在");
            }
            if (c.getUserId() != null && !c.getUserId().equals(userId)) {
                SysUser holder = userMapper.selectById(c.getUserId());
                throw new BizException(409, "食堂「" + c.getName() + "」已绑定账号 "
                        + (holder == null ? "(已被删除)" : holder.getUsername()) + "，请先解绑或改绑该账号");
            }
            if (!keepCanteen) {
                canteenMapper.update(null, new LambdaUpdateWrapper<Canteen>()
                        .set(Canteen::getUserId, userId).eq(Canteen::getId, canteenId));
            }
        }
        // ADMIN / INSPECTOR：只需账号+角色，不绑定业务档案（上方已清空旧绑定）
    }

    /** 对外返回的账号信息，永不含密码 */
    private Map<String, Object> safeUser(Long id) {
        SysUser u = userMapper.selectById(id);
        Map<String, Object> m = new LinkedHashMap<>();
        if (u == null) {
            return m;
        }
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("realName", u.getRealName());
        m.put("phone", u.getPhone());
        m.put("roleType", u.getRoleType());
        m.put("roleName", LogService.roleName(u.getRoleType()));
        m.put("status", u.getStatus());
        m.put("needChangePwd", u.getNeedChangePwd());
        Farmer f = farmerMapper.selectOne(new LambdaQueryWrapper<Farmer>()
                .eq(Farmer::getUserId, id).last("LIMIT 1"));
        Canteen c = canteenMapper.selectOne(new LambdaQueryWrapper<Canteen>()
                .eq(Canteen::getUserId, id).last("LIMIT 1"));
        if (f != null) {
            m.put("farmerId", f.getId());
            m.put("orgName", f.getName());
            m.put("orgNo", f.getFarmerNo());
        } else if (c != null) {
            m.put("canteenId", c.getId());
            m.put("orgName", c.getName());
            m.put("orgNo", c.getCanteenNo());
        } else {
            m.put("orgName", "平台");
        }
        return m;
    }

    // ------------------------------------------------------------------ 状态与密码

    @Transactional
    public void changeStatus(Long id, Integer status) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(404, "用户不存在");
        }
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(400, "状态值非法");
        }
        LoginUser me = UserContext.getOrNull();
        if (me != null && me.getUserId().equals(id) && status == 0) {
            throw new BizException(400, "不能停用当前登录的账号，请换一个管理员操作");
        }
        if (status == 0) {
            boolean isAdmin = "ADMIN".equals(u.getRoleType());
            if (isAdmin && userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getRoleType, "ADMIN").eq(SysUser::getStatus, 1)) <= 1) {
                throw new BizException(400, "这是最后一个启用状态的管理员账号，停用后将无人可管理系统");
            }
            u.setOnline(0);
        }
        u.setStatus(status);
        userMapper.updateById(u);
        if (status == 0) {
            revocationStore.revokeUser(id);
            // 定向事件：被停用账号即使正在别的页面，也会在秒级内收到通知并立即下线
            realtimeService.publish("ACCOUNT_DISABLED", "账号安全",
                    "管理员已停用账号「" + u.getUsername() + "」，当前登录已失效",
                    null, id, "/api/admin/users/" + id + "/status", UserContext.get());
        }
        logService.record("用户管理", "用户 " + u.getUsername() + (status == 1 ? " 启用" : " 停用"));
    }

    /** 返回一次性随机初始密码，由管理员自行转交；旧 token 全部吊销，并要求下次登录改密。 */
    @Transactional
    public String resetPassword(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(404, "用户不存在");
        }
        String newPassword = randomPassword();
        u.setPassword(encoder.encode(newPassword));
        u.setLoginFail(0);
        u.setLockUntil(null);
        u.setNeedChangePwd(1);
        u.setPwdUpdateTime(null);
        userMapper.updateById(u);
        revocationStore.revokeUser(id);
        logService.record("用户管理", "重置用户 " + u.getUsername() + " 的登录密码（随机一次性，需首登改密）");
        return newPassword;
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", userMapper.selectCount(null));
        m.put("admins", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleType, "ADMIN")));
        m.put("farmers", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleType, "FARMER")));
        m.put("purchasers", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleType, "PURCHASER")));
        m.put("inspectors", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleType, "INSPECTOR")));
        m.put("online", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getOnline, 1)));
        m.put("pendingPwd", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getNeedChangePwd, 1)));
        return m;
    }
}
