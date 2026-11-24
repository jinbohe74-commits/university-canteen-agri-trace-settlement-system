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

    /** 绑定的农户 / 食堂档案 id：为空说明账号没有落到具体业务对象上 */
    private Long farmerId;
    private Long canteenId;

    /** 档案编号，便于管理员核对"这个账号到底是谁" */
    private String orgNo;

    /** 是否处于登录锁定（连续输错 5 次） */
    private Boolean locked;
}
