package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("settlement_item")
public class SettlementItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long settlementId;
    private String itemName;
    private String itemType;
    private BigDecimal amount;
    private String remark;
}
