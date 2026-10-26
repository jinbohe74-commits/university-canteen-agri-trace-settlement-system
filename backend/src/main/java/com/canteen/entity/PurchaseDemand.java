package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("purchase_demand")
public class PurchaseDemand {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String demandNo;
    private Long canteenId;
    private Long productId;
    private BigDecimal quantity;
    private BigDecimal expectedPrice;
    private LocalDateTime deadline;
    private String deliveryMode;
    private String settlementMode;
    private String qualityReq;
    private Integer quoteCount;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
