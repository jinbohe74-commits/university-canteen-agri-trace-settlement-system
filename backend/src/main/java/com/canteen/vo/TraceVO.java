package com.canteen.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 批次溯源全链路视图
 */
@Data
public class TraceVO {
    private String batchNo;
    private String hash;
    private String qrCode;
    private String orderNo;
    private String productName;
    private String productNo;
    private String categoryName;
    private String unit;
    private String farmerName;
    private String farmerType;
    private String farmerAddress;
    private BigDecimal creditScore;
    private String canteenName;
    private String campus;
    private BigDecimal orderQuantity;
    private BigDecimal receiveQuantity;
    private BigDecimal lossQuantity;
    private BigDecimal lossRate;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private BigDecimal actualAmount;
    private String qualityResult;
    private String inspector;
    private String inspectTime;
    private List<Map<String, Object>> qualityItems;
    private String deliveryNo;
    private String vehicle;
    private String driver;
    private BigDecimal temperature;
    private BigDecimal mileage;
    private String inboundTime;
    private String chainHash;
    private List<Map<String, Object>> nodes;
    private Boolean complete;
}
