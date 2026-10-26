package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("trace_record")
public class TraceRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String batchNo;
    private Long orderId;
    private Long farmerId;
    private Long productId;
    private Long qualityReportId;
    private Long deliveryId;
    private Long inboundId;
    private String hash;
    private String qrCode;
    private String chain;
    private LocalDateTime createTime;
}
