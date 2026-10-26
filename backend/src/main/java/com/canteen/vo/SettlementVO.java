package com.canteen.vo;

import com.canteen.entity.Settlement;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class SettlementVO extends Settlement {
    private String orderNo;
    private String productName;
    private String unit;
    private String farmerName;
    private String canteenName;
    private BigDecimal orderQuantity;
    private BigDecimal receiveQuantity;
    private BigDecimal unitPrice;
    private BigDecimal lossRate;
    private String statusName;
}
