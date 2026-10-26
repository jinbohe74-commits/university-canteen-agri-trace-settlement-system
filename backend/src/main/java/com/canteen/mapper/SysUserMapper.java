package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.SysUser;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT r.role_code FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId} LIMIT 1")
    String selectRoleCode(Long userId);

    @Select("SELECT m.perms FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id = m.id " +
            "WHERE rm.role_id = (SELECT role_id FROM sys_user_role WHERE user_id = #{userId} LIMIT 1) " +
            "AND m.perms IS NOT NULL")
    List<String> selectPerms(Long userId);

    @Select("SELECT m.id FROM sys_menu m JOIN sys_role_menu rm ON rm.menu_id = m.id " +
            "WHERE rm.role_id = (SELECT role_id FROM sys_user_role WHERE user_id = #{userId} LIMIT 1)")
    List<Long> selectMenuIds(Long userId);
}
