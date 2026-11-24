package com.canteen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.canteen.entity.StockOut;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

public interface StockOutMapper extends BaseMapper<StockOut> {

    @Select("SELECT o.id, o.product_id AS productId, p.name AS productName, o.quantity, p.unit, " +
            "       o.reason, o.operator, o.remark, o.create_time AS createTime " +
            "  FROM stock_out o JOIN product p ON p.id = o.product_id " +
            " WHERE o.canteen_id = #{canteenId} " +
            " ORDER BY o.id DESC LIMIT #{limit}")
    List<Map<String, Object>> recent(@Param("canteenId") Long canteenId, @Param("limit") int limit);
}
