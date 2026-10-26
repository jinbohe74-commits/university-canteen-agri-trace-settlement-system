package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("inbound_record")
public class InboundRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long inspectorId;
    private Long operatorCanteenId;
    private BigDecimal orderQuantity;
    private BigDecimal receiveQuantity;
    private BigDecimal lossQuantity;
    private BigDecimal lossRate;
    private Integer isPartial;
    private String qualityResult;
    private String operatorName;
    private String remark;
    private LocalDateTime inboundTime;
}
