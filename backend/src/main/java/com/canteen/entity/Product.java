package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product")
public class Product {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String productNo;
    private String name;
    private Long categoryId;
    private String unit;
    private BigDecimal avgPrice;
    private BigDecimal priceMin;
    private BigDecimal priceMax;
    private Integer farmerCount;
    private String storageReq;
    private String status;
    private String description;
    private LocalDateTime createTime;
}
