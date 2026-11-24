package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 出库/领用流水：采购建议的唯一消耗依据 */
@Data
@TableName("stock_out")
public class StockOut {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long canteenId;
    private Long productId;
    private BigDecimal quantity;
    /** 领用 / 损耗 / 退货 / 盘点调整 */
    private String reason;
    private String operator;
    private String remark;
    private LocalDateTime createTime;
}
