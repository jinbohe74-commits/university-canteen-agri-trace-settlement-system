package com.canteen.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 农户对某个采购需求的报价 */
@Data
public class QuoteDTO {

    @NotNull(message = "请选择要报价的需求")
    private Long demandId;

    /** 可选：绑定自己的在售货源；不填则中标时自动按报价挂牌 */
    private Long sourceId;

    @NotNull(message = "请填写报价单价")
    @DecimalMin(value = "0.01", message = "报价单价必须大于 0")
    private BigDecimal price;

    @NotNull(message = "请填写可供数量")
    @DecimalMin(value = "0.01", message = "可供数量必须大于 0")
    private BigDecimal quantity;

    @Min(value = 1, message = "承诺送达天数至少 1 天")
    private Integer deliveryDays = 1;

    @Size(max = 200, message = "质量承诺不能超过 200 字")
    private String qualityNote;

    @Size(max = 200, message = "备注不能超过 200 字")
    private String remark;
}
