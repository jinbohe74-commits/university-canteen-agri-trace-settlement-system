package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("settlement")
public class Settlement {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String settlementNo;
    private Long orderId;
    private Long farmerId;
    private Long canteenId;
    private BigDecimal totalAmount;
    private BigDecimal lossDeduction;
    private BigDecimal deduction;
    private BigDecimal actualAmount;
    private String status;
    private String auditor;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime confirmTime;
}
