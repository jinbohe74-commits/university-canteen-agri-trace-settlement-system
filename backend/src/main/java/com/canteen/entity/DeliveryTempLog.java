package com.canteen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 冷链车厢温度上报流水。
 *
 * <p>原来温度只是 {@code delivery_order.temperature} 一个会被覆盖的字段，
 * 无法回答「一路上有没有断链」。动态地图的温度曲线全部来自这张表。</p>
 */
@Data
@TableName("delivery_temp_log")
public class DeliveryTempLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deliveryId;

    private BigDecimal temperature;

    /** 1 = 超出 2~8℃ */
    private Integer alarm;

    /** depart=发车 / manual=手动上报 / sensor=设备上报 */
    private String source;

    private LocalDateTime reportTime;
}
