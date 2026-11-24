package com.canteen.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 修复 P2：入库请求补 Bean Validation，空请求返回 400 而非 404/500。 */
@Data
public class InboundDTO {

    @NotNull(message = "请选择要入库的订单")
    private Long orderId;

    @NotNull(message = "请填写实收数量")
    @DecimalMin(value = "0.01", message = "实收数量必须大于 0")
    private BigDecimal receiveQuantity;

    /**
     * 已移除 qualityResult 字段（2026-11-10）。
     * 原来前端可以在入库时自己选「质检合格 / 不合格」，但这值根本不参与判定，
     * 真正的扣款依据是库里的质检报告——留着只会让人误以为能改结论。
     */
    @Size(max = 200, message = "备注不能超过 200 字")
    private String remark;
}
