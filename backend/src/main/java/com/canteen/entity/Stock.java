package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 食堂库存：入库加、领用减，按（食堂, 品项）唯一 */
@Data
@TableName("stock")
public class Stock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long canteenId;
    private Long productId;
    private BigDecimal quantity;
    private String unit;
    /** 安全库存：低于此值进入补货提醒 */
    private BigDecimal safetyQty;
    private LocalDateTime updateTime;
}
