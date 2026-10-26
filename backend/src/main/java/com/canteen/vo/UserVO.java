package com.canteen.vo;

import com.canteen.entity.SysUser;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UserVO extends SysUser {
    private String roleCode;
    private String roleName;
    private String lastLoginText;
    private String orgName;
}
