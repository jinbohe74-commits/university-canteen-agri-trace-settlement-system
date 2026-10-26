package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("farmer")
public class Farmer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String farmerNo;
    private Long userId;
    private String name;
    private String type;
    private String contact;
    private String phone;
    private String mainCategory;
    private String supplyCapacity;
    private String address;
    private String certNo;
    private String certFile;
    private LocalDate certExpire;
    private BigDecimal distanceKm;
    private BigDecimal creditScore;
    private String status;
    private Integer dealCount;
    private BigDecimal goodRate;
    private LocalDateTime joinTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
