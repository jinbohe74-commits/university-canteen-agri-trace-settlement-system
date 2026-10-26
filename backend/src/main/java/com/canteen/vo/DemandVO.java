package com.canteen.vo;

import com.canteen.entity.PurchaseDemand;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DemandVO extends PurchaseDemand {
    private String productName;
    private String categoryName;
    private String unit;
    private String canteenName;
    private Integer matchCount;
    private Long orderId;
    private String orderNo;
}
