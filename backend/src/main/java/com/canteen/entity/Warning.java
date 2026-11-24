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
    /** 现场照片，分号分隔的相对地址；质检员上报异常时真实上传 */
    private String photoUrls;
    private String status;
    private LocalDateTime triggerTime;
    private String handler;
    private LocalDateTime handleTime;
    private String handleRemark;
}
