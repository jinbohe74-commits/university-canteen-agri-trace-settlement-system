package com.canteen.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.canteen.common.BizException;
import com.canteen.common.PageResult;
import com.canteen.entity.*;
import com.canteen.mapper.*;
import com.canteen.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 用户、角色与权限（RBAC）
 */
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
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
        return PageResult.of(p, u -> {
            UserVO v = new UserVO();
            v.setId(u.getId());
            v.setUsername(u.getUsername());
            v.setRealName(u.getRealName());
            v.setPhone(u.getPhone());
            v.setRoleType(u.getRoleType());
            v.setAvatar(u.getAvatar());
            v.setStatus(u.getStatus());
            v.setOnline(u.getOnline());
            v.setLastLoginTime(u.getLastLoginTime());
            v.setCreateTime(u.getCreateTime());
            v.setRoleCode(u.getRoleType());
            v.setRoleName(LogService.roleName(u.getRoleType()));
            v.setLastLoginText(u.getLastLoginTime() == null ? "从未登录" : u.getLastLoginTime().format(FMT));
            Farmer f = farmers.get(u.getId());
            Canteen c = canteens.get(u.getId());
            v.setOrgName(f != null ? f.getName() : (c != null ? c.getName() : "平台"));
            return v;
        });
    }

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

    @Transactional
    public SysUser save(SysUser u, String password, Long roleId) {
        if (u.getId() == null) {
            if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                    .eq(SysUser::getUsername, u.getUsername())) > 0) {
                throw new BizException("账号已存在");
            }
            u.setPassword(encoder.encode(StringUtils.hasText(password) ? password : "123456"));
            if (u.getStatus() == null) {
                u.setStatus(1);
            }
            u.setOnline(0);
            userMapper.insert(u);
            if (roleId != null) {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(u.getId());
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
            logService.record("用户管理", "新增用户 " + u.getUsername() + " 并分配角色");
        } else {
            SysUser old = userMapper.selectById(u.getId());
            if (old == null) {
                throw new BizException(404, "用户不存在");
            }
            u.setUsername(old.getUsername());
            if (StringUtils.hasText(password)) {
                u.setPassword(encoder.encode(password));
            }
            userMapper.updateById(u);
            if (roleId != null) {
                userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, u.getId()));
                SysUserRole ur = new SysUserRole();
                ur.setUserId(u.getId());
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
            logService.record("用户管理", "编辑用户 " + u.getUsername());
        }
        return u;
    }

    @Transactional
    public void changeStatus(Long id, Integer status) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(404, "用户不存在");
        }
        if (u.getId() == 1L && status == 0) {
            throw new BizException("系统内置管理员账号不允许停用");
        }
        u.setStatus(status);
        userMapper.updateById(u);
        logService.record("用户管理", "用户 " + u.getUsername() + (status == 1 ? " 启用" : " 停用"));
    }

    @Transactional
    public void resetPassword(Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) {
            throw new BizException(404, "用户不存在");
        }
        u.setPassword(encoder.encode("123456"));
        u.setLoginFail(0);
        u.setLockUntil(null);
        userMapper.updateById(u);
        logService.record("用户管理", "重置用户 " + u.getUsername() + " 密码为 123456");
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", userMapper.selectCount(null));
        m.put("admins", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleType, "ADMIN")));
        m.put("farmers", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleType, "FARMER")));
        m.put("purchasers", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleType, "PURCHASER")));
        m.put("inspectors", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getRoleType, "INSPECTOR")));
        m.put("online", userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getOnline, 1)));
        return m;
    }
}

