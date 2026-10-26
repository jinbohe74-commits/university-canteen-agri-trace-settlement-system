package com.canteen.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class QualitySubmitDTO {
    private Long orderId;
    private String batchNo;
    private BigDecimal sampleQty;
    /** 合格 / 不合格 */
    private String result;
    private List<Map<String, Object>> items;
    private String remark;
    private String reportFile;
}
