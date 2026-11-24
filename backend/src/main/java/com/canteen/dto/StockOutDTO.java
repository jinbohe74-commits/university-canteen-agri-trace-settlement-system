package com.canteen.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 食堂库存出库（领用/损耗/盘点调整） */
@Data
public class StockOutDTO {

    @NotNull(message = "请选择出库品项")
    private Long productId;

    @NotNull(message = "请填写出库数量")
    @DecimalMin(value = "0.01", message = "出库数量必须大于 0")
    private BigDecimal quantity;

    /** 领用 / 损耗 / 退货 / 盘点调整 */
    private String reason = "领用";

    @Size(max = 200, message = "备注不能超过 200 字")
    private String remark;
}
