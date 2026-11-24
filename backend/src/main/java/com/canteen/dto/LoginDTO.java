package com.canteen.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginDTO {

    @NotBlank(message = "请输入用户名")
    private String username;

    @NotBlank(message = "请输入密码")
    private String password;

    private Boolean remember = false;

    /** 登录页选中的期望角色（可选）；非空时服务端校验账号实际角色，避免装饰性控件 */
    private String expectedRole;
}
