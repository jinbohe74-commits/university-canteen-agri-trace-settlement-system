package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("orders")
public class Orders {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    /** 所属采购单：一次提交多个品项时，各履约子单共用同一个采购单 */
    private Long poId;
    private Long demandId;
    private Long sourceId;
    private Long farmerId;
    private Long canteenId;
    private Long productId;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private Integer status;
    private String batchNo;
    private LocalDateTime expectedDelivery;
    private LocalDateTime actualDelivery;
    private String remark;
    @Version
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
