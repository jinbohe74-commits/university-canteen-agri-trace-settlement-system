package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("supply_source")
public class SupplySource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long farmerId;
    private Long productId;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal price;
    private LocalDate supplyStart;
    private LocalDate supplyEnd;
    private String deliveryMode;
    private String storageReq;
    private String originRemark;
    private String images;
    private BigDecimal soldQuantity;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
