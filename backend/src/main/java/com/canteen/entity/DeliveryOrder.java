package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("delivery_order")
public class DeliveryOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deliveryNo;
    private Long orderId;
    private String vehicle;
    private String driver;
    private String driverPhone;
    private BigDecimal temperature;
    private BigDecimal mileage;
    private String origin;
    private String destination;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime arriveTime;
    private String traces;
    private LocalDateTime createTime;
}
