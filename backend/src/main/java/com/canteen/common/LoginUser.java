package com.canteen.common;

import lombok.Data;

/**
 * 登录用户上下文（由 JWT 解析得到）
 */
@Data
public class LoginUser {

    private Long userId;
    private String username;
    private String realName;
    /** ADMIN / FARMER / PURCHASER / INSPECTOR */
    private String role;
    /** 农户 ID（农户角色） */
    private Long farmerId;
    /** 食堂 ID（采购员角色） */
    private Long canteenId;
}
