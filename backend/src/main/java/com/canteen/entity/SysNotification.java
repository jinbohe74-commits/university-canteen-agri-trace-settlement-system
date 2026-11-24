package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 定向业务通知：一条只属于一个接收人，不再全员广播 */
@Data
@TableName("sys_notification")
public class SysNotification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String bizType;
    private String bizNo;
    private Long orderId;
    private Long senderId;
    private String senderName;
    private String senderRole;
    private Long recipientId;
    private String recipientName;
    private String recipientRole;
    private String title;
    private String content;
    private String route;
    private Integer isRead;
    private LocalDateTime readTime;
    private LocalDateTime createTime;
}
