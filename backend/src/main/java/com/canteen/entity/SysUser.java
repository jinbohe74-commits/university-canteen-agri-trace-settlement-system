package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;

    /** BCrypt 密文：任何接口都不得把它序列化出去（曾随新增用户响应泄露） */
    @JsonIgnore
    private String password;
    private String realName;
    private String phone;
    private String roleType;
    private String avatar;
    private Integer status;

    /** 1 = 首次登录/被重置后必须先改密码，否则只放行 /api/auth/** */
    private Integer needChangePwd;
    private LocalDateTime pwdUpdateTime;
    private Integer online;
    private Integer loginFail;
    private LocalDateTime lockUntil;
    private LocalDateTime lastLoginTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
