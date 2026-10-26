package com.canteen.vo;

import com.canteen.entity.Evaluation;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class EvaluationVO extends Evaluation {
    private String orderNo;
    private String productName;
    private String unit;
    private String farmerName;
    private String canteenName;
    private java.math.BigDecimal quantity;
    private java.math.BigDecimal receiveQuantity;
    private java.math.BigDecimal actualAmount;
    private java.math.BigDecimal creditScore;
    private Integer dealCount;
    private java.math.BigDecimal goodRate;
    private Boolean evaluated;
}
