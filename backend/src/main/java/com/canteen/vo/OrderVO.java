package com.canteen.vo;

import com.canteen.entity.Orders;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class OrderVO extends Orders {
    private String productName;
    private String categoryName;
    private String unit;
    private String farmerName;
    private String farmerType;
    private String canteenName;
    private String statusName;
    private BigDecimal receiveQuantity;
    private BigDecimal lossQuantity;
    private BigDecimal lossRate;
    private Boolean hasEvaluation;
    private String deliveryStatus;
    private String settlementStatus;
    private Long settlementId;
}
