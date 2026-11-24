package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跨角色实时事件。任何写操作都会由 AOP 落一条，SSE 推送 + 轮询兜底共用这一份事实。
 */
@Data
@TableName("sys_event")
public class SysEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventType;
    private String module;
    private String content;
    private Long operatorId;
    private String operatorName;
    private String operatorRole;
    /** 逗号分隔的目标角色；空串表示全员可见 */
    private String targetRoles;
    /** 定向接收人（如账号停用），非空时忽略 targetRoles */
    private Long targetUserId;
    private String refPath;
    private LocalDateTime createTime;
}
