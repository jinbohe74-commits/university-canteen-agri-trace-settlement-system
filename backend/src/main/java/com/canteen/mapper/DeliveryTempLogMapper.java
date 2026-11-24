package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.DeliveryTempLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DeliveryTempLogMapper extends BaseMapper<DeliveryTempLog> {

    /** 最近 N 条上报，按时间正序返回给图表 */
    @Select("SELECT * FROM delivery_temp_log WHERE delivery_id = #{deliveryId} " +
            "ORDER BY report_time DESC, id DESC LIMIT #{limit}")
    List<DeliveryTempLog> recent(@Param("deliveryId") Long deliveryId, @Param("limit") int limit);
}
