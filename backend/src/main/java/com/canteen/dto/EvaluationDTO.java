package com.canteen.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class EvaluationDTO {
    private Long orderId;
    private BigDecimal overallScore;
    private BigDecimal qualityScore;
    private BigDecimal freshScore;
    private BigDecimal deliveryScore;
    private BigDecimal serviceScore;
    private BigDecimal packageScore;
    private List<String> tags;
    private String content;
}
