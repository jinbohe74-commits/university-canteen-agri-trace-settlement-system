package com.canteen.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 智能匹配结果（五因子加权）
 */
@Data
public class MatchVO {
    private Integer rank;
    private Long sourceId;
    private Long farmerId;
    private String farmerName;
    private String farmerType;
    private String productName;
    private String unit;
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal creditScore;
    private BigDecimal distanceKm;
    private BigDecimal score;
    private BigDecimal categoryScore;
    private BigDecimal priceScore;
    private BigDecimal quantityScore;
    private BigDecimal creditFactor;
    private BigDecimal distanceScore;
    private Boolean best;
    private Boolean quantityEnough;
    private Integer dealCount;
}
