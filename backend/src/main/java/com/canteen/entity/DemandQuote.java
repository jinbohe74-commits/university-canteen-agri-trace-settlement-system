package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 需求报价：农户对采购需求的一次响应，采购员据此横向比价并选定中标。 */
@Data
@TableName("demand_quote")
public class DemandQuote {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long demandId;
    private Long farmerId;
    /** 指定货源，可空；为空时中标会自动按报价挂牌 */
    private Long sourceId;
    private BigDecimal price;
    private BigDecimal quantity;
    private Integer deliveryDays;
    private String qualityNote;
    private String remark;
    /** 待选 / 中标 / 落选 */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
