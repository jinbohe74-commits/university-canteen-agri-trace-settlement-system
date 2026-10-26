package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("warning")
public class Warning {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String warningNo;
    private String riskLevel;
    private String warningType;
    private String target;
    private String content;
    private String status;
    private LocalDateTime triggerTime;
    private String handler;
    private LocalDateTime handleTime;
    private String handleRemark;
}
