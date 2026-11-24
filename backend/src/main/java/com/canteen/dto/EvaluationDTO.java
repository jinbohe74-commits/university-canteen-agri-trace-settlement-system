package com.canteen.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 修复 P2：评价提交补 Bean Validation，评分统一限制 0~5。 */
@Data
public class EvaluationDTO {

    @NotNull(message = "请选择要评价的订单")
    private Long orderId;

    @DecimalMin(value = "0", message = "评分不能小于 0")
    @DecimalMax(value = "5", message = "评分不能大于 5")
    private BigDecimal overallScore;

    @DecimalMin(value = "0", message = "评分不能小于 0")
    @DecimalMax(value = "5", message = "评分不能大于 5")
    private BigDecimal qualityScore;

    @DecimalMin(value = "0", message = "评分不能小于 0")
    @DecimalMax(value = "5", message = "评分不能大于 5")
    private BigDecimal freshScore;

    @DecimalMin(value = "0", message = "评分不能小于 0")
    @DecimalMax(value = "5", message = "评分不能大于 5")
    private BigDecimal deliveryScore;

    @DecimalMin(value = "0", message = "评分不能小于 0")
    @DecimalMax(value = "5", message = "评分不能大于 5")
    private BigDecimal serviceScore;

    @DecimalMin(value = "0", message = "评分不能小于 0")
    @DecimalMax(value = "5", message = "评分不能大于 5")
    private BigDecimal packageScore;

    private List<String> tags;

    @Size(max = 500, message = "评价内容不能超过 500 字")
    private String content;
}
