package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("quality_report")
public class QualityReport {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private String batchNo;
    private Long inspectorId;
    private String inspector;
    private BigDecimal sampleQty;
    private String result;
    private String items;
    private String standards;
    private String reportFile;
    private String remark;
    private String hash;
    private LocalDateTime inspectTime;
}
