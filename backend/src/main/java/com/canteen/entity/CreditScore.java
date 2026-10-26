package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("credit_score")
public class CreditScore {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long farmerId;
    private Long orderId;
    private BigDecimal beforeScore;
    private BigDecimal changeScore;
    private BigDecimal afterScore;
    private String reason;
    private LocalDateTime createTime;
}
