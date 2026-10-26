package com.canteen.vo;

import com.canteen.entity.SupplySource;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class SourceVO extends SupplySource {
    private String productName;
    private String productNo;
    private String categoryName;
    private String farmerName;
    private String farmerType;
    private BigDecimal creditScore;
    private BigDecimal distanceKm;
    private BigDecimal avgPrice;
}
