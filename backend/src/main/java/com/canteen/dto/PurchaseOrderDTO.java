package com.canteen.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 一次采购单提交：可含多个货源（品项）。 */
@Data
public class PurchaseOrderDTO {

    /** 可选：由某张采购需求转单而来 */
    private Long demandId;

    private LocalDateTime expectedDelivery;

    @Size(max = 200, message = "备注不能超过 200 字")
    private String remark;

    @NotEmpty(message = "请至少选择一个货源")
    @Valid
    private List<Item> items;

    @Data
    public static class Item {

        @NotNull(message = "请选择货源")
        private Long sourceId;

        @NotNull(message = "请填写采购数量")
        @DecimalMin(value = "0.01", message = "采购数量必须大于 0")
        private BigDecimal quantity;
    }
}
