package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购单：采购员的一次提交，可含多个品项。
 * 每个品项落一张 orders（履约单元），质检、配送、入库、结算、损耗仍按订单处理。
 */
@Data
@TableName("purchase_order")
public class PurchaseOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String poNo;
    private Long canteenId;
    private Long purchaserId;
    private Long demandId;
    private Integer itemCount;
    private BigDecimal totalAmount;
    private LocalDateTime expectedDelivery;
    private String status;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
