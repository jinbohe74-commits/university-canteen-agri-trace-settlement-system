package com.canteen.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class InboundDTO {
    private Long orderId;
    private BigDecimal receiveQuantity;
    private String qualityResult = "合格";
    private String remark;
}
