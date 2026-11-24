package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("canteen")
public class Canteen {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String canteenNo;
    private Long userId;
    private String name;
    private String campus;

    /** 食堂经度（配送动态地图终点） */
    private BigDecimal lng;

    /** 食堂纬度 */
    private BigDecimal lat;
    private String manager;
    private String phone;
    private Integer scale;
    private Integer supplierCount;
    private Integer monthlyOrders;
    private BigDecimal monthlyAmount;
    private String status;
    private LocalDateTime createTime;
}
