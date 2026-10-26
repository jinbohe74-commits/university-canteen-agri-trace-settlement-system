package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("evaluation")
public class Evaluation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long canteenId;
    private Long farmerId;
    private BigDecimal overallScore;
    private BigDecimal qualityScore;
    private BigDecimal freshScore;
    private BigDecimal deliveryScore;
    private BigDecimal serviceScore;
    private BigDecimal packageScore;
    private String tags;
    private String content;
    private LocalDateTime createTime;
}
